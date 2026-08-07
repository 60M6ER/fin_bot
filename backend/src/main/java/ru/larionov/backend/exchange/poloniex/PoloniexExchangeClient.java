package ru.larionov.backend.exchange.poloniex;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.AccountsApi;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.FeesApi;
import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.MarketDataStreamService;
import ru.larionov.backend.exchange.api.OperationsStreamService;
import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.OrdersStreamService;
import ru.larionov.backend.exchange.api.TradingCalendarApi;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

import java.util.List;
import java.util.Optional;

/**
 * Клиент Poloniex: собирает шесть API и два стрима поверх одного HTTP-транспорта.
 *
 * Стримы создаются сразу, а не по требованию: подписка на них происходит при старте
 * бота, и городить ленивую инициализацию с блокировками ради экономии двух объектов
 * значило бы усложнить самое чувствительное к гонкам место.
 */
@Slf4j
public class PoloniexExchangeClient implements ExchangeClient {

    private final PoloniexRest rest;
    private final PoloniexSymbols symbols;

    private final InstrumentsApi instruments;
    private final MarketDataApi marketData;
    private final OrdersApi orders;
    private final AccountsApi accounts;
    private final FeesApi fees;
    private final TradingCalendarApi calendar;

    private final PoloniexMarketDataStreamService marketDataStream;
    private final PoloniexOrdersStreamService ordersStream;

    public PoloniexExchangeClient(String host, String publicWsUrl, String privateWsUrl,
                                  String apiKey, String secret) {
        this.rest = new PoloniexRest(host, apiKey, secret);
        this.symbols = new PoloniexSymbols(rest);

        this.instruments = new PoloniexInstrumentsApi(symbols);
        this.marketData = new PoloniexMarketDataApi(rest, symbols);
        this.orders = new PoloniexOrdersApi(rest, symbols);
        this.accounts = new PoloniexAccountsApi(rest, symbols);
        this.fees = new PoloniexFeesApi(rest);
        this.calendar = new PoloniexTradingCalendarApi();

        this.marketDataStream = new PoloniexMarketDataStreamService(publicWsUrl);
        this.ordersStream = new PoloniexOrdersStreamService(privateWsUrl, apiKey, secret, symbols);
    }

    @Override
    public InstrumentsApi instruments() {
        return instruments;
    }

    @Override
    public MarketDataApi marketData() {
        return marketData;
    }

    @Override
    public OrdersApi orders() {
        return orders;
    }

    @Override
    public TradingCalendarApi calendar() {
        return calendar;
    }

    @Override
    public AccountsApi accounts() {
        return accounts;
    }

    @Override
    public FeesApi fees() {
        return fees;
    }

    @Override
    public Optional<MarketDataStreamService> marketDataStream() {
        return Optional.of(marketDataStream);
    }

    /** Отдельного потока операций нет: позиции даёт ордерный стрим плюс REST-сверка. */
    @Override
    public Optional<OperationsStreamService> operationsStream() {
        return Optional.empty();
    }

    @Override
    public Optional<OrdersStreamService> ordersStream() {
        return Optional.of(ordersStream);
    }

    @Override
    public ExchangeMeta meta() {
        return new ExchangeMeta(
                ExchangeType.POLONIEX,
                // Расписания нет: спот торгуется круглосуточно.
                false,
                true,
                true,
                // Фьючерсы у биржи есть, но этот адаптер их не поддерживает,
                // и заявлять поддержку значило бы обмануть UI.
                false,
                // Публичной песочницы для спота нет: первая проверка идёт
                // на реальных деньгах, поэтому минимальными суммами.
                false,
                // Деньгами на споте служат стейблкойны — ими котируются пары и
                // ими же считается бюджет бота. Остальные монеты на кошельке это
                // позиции, даже когда их по числу больше.
                List.of("USDT", "USDC"),
                // Ставка приходит из /feeinfo и зависит от оборота за 30 дней.
                true);
    }

    public StreamHealth marketDataStreamHealth() {
        return marketDataStream.health();
    }

    public StreamHealth ordersStreamHealth() {
        return ordersStream.health();
    }

    public PoloniexSymbols symbols() {
        return symbols;
    }

    @Override
    public void close() {
        try {
            marketDataStream.close();
        } catch (Exception e) {
            log.debug("Закрытие рыночного стрима Poloniex: {}", e.getMessage());
        }
        try {
            ordersStream.close();
        } catch (Exception e) {
            log.debug("Закрытие ордерного стрима Poloniex: {}", e.getMessage());
        }
        rest.close();
    }
}
