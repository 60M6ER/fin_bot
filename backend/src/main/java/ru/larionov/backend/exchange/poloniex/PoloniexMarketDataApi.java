package ru.larionov.backend.exchange.poloniex;

import lombok.RequiredArgsConstructor;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.CandlesQuery;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рыночные данные Poloniex.
 *
 * Торговый статус берётся из состояния пары, а не из расписания: у криптобиржи
 * расписания нет, зато есть режимы {@code PAUSE} и {@code POST_ONLY}. Последний
 * особенно коварен — торги идут, но обычная лимитная заявка не принимается, поэтому
 * {@code limitOrdersAvailable} для него false.
 */
@RequiredArgsConstructor
public class PoloniexMarketDataApi implements MarketDataApi {

    private final PoloniexRest rest;
    private final PoloniexSymbols symbols;

    @Override
    public LastPrice getLastPrice(InstrumentId instrumentId) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        var price = rest.call("цена " + symbol, rest.api().price(symbol));
        if (price == null || price.getPrice() == null) {
            throw new IllegalStateException("Poloniex не отдал цену " + symbol);
        }
        return new LastPrice(
                instrumentId,
                new Price(new BigDecimal(price.getPrice()), quoteOf(symbol)),
                price.getTime() == null ? Instant.now() : Instant.ofEpochMilli(price.getTime()));
    }

    @Override
    public Map<InstrumentId, LastPrice> getLastPrices(Collection<InstrumentId> instrumentIds) {
        if (instrumentIds == null || instrumentIds.isEmpty()) {
            return Map.of();
        }

        // Один запрос на все пары вместо цикла: у биржи есть пакетный эндпоинт,
        // и ходить по одной означало бы упереться в лимит запросов на ровном месте.
        Map<String, InstrumentId> wanted = new HashMap<>();
        for (InstrumentId id : instrumentIds) {
            wanted.put(PoloniexSymbols.symbolOf(id), id);
        }

        Map<InstrumentId, LastPrice> result = new HashMap<>();
        var prices = rest.call("цены", rest.api().prices());
        if (prices == null) {
            return result;
        }
        for (var price : prices) {
            InstrumentId id = wanted.get(price.getSymbol());
            if (id == null || price.getPrice() == null) {
                continue;
            }
            result.put(id, new LastPrice(
                    id,
                    new Price(new BigDecimal(price.getPrice()), quoteOf(price.getSymbol())),
                    price.getTime() == null ? Instant.now() : Instant.ofEpochMilli(price.getTime())));
        }
        return result;
    }

    @Override
    public OrderBook getOrderBook(InstrumentId instrumentId, int depth) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        var book = rest.call("стакан " + symbol, rest.api().orderBook(symbol, depth <= 0 ? 5 : depth));
        String quote = quoteOf(symbol);

        return new OrderBook(
                instrumentId,
                depth,
                levels(book == null ? null : book.getBids(), quote),
                levels(book == null ? null : book.getAsks(), quote),
                // Ценовых планок у криптобиржи нет.
                null,
                null,
                book == null || book.getTime() == null ? Instant.now() : Instant.ofEpochMilli(book.getTime()));
    }

    /**
     * Стакан приходит плоским списком: цена, объём, цена, объём…
     * Позиционный формат — источник тихих ошибок, поэтому разбор живёт в одном месте.
     */
    private static List<OrderBookLevel> levels(List<String> flat, String quote) {
        if (flat == null || flat.isEmpty()) {
            return List.of();
        }
        List<OrderBookLevel> levels = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            levels.add(new OrderBookLevel(
                    new Price(new BigDecimal(flat.get(i)), quote),
                    new BigDecimal(flat.get(i + 1))));
        }
        return levels;
    }

    @Override
    public List<Candle> getCandles(InstrumentId instrumentId, CandlesQuery query) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        String quote = quoteOf(symbol);

        var raw = rest.call("свечи " + symbol, rest.api().candles(
                symbol,
                interval(query.interval()),
                500,
                query.from() == null ? null : query.from().toEpochMilli(),
                query.to() == null ? null : query.to().toEpochMilli()));

        List<Candle> candles = new ArrayList<>();
        if (raw == null) {
            return candles;
        }
        for (List<String> row : raw) {
            // Позиции по документации: low, high, open, close, ..., startTime(9), ..., closeTime(13).
            if (row.size() < 14) {
                continue;
            }
            candles.add(new Candle(
                    instrumentId,
                    new Price(new BigDecimal(row.get(2)), quote),
                    new Price(new BigDecimal(row.get(1)), quote),
                    new Price(new BigDecimal(row.get(0)), quote),
                    new Price(new BigDecimal(row.get(3)), quote),
                    new BigDecimal(row.get(5)),
                    Instant.ofEpochMilli(Long.parseLong(row.get(9))),
                    Instant.ofEpochMilli(Long.parseLong(row.get(13)))));
        }
        return candles;
    }

    @Override
    public TradingStatusEvent getTradingStatus(InstrumentId instrumentId) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        String state = symbols.find(symbol).map(m -> m.getState()).orElse("UNKNOWN");

        boolean normal = "NORMAL".equalsIgnoreCase(state);
        // POST_ONLY: торги идут, но обычную лимитную заявку биржа не примет.
        // Считать такой режим торгуемым — значит всю его длительность получать отказы.
        boolean postOnly = "POST_ONLY".equalsIgnoreCase(state);

        return new TradingStatusEvent(
                instrumentId,
                normal || postOnly,
                normal,
                state,
                Instant.now());
    }

    /** Отображение наших интервалов в обозначения Poloniex. */
    private static String interval(CandleInterval interval) {
        return switch (interval == null ? CandleInterval.H1 : interval) {
            case M1 -> "MINUTE_1";
            case M5 -> "MINUTE_5";
            case M15 -> "MINUTE_15";
            case H1 -> "HOUR_1";
            case D1 -> "DAY_1";
        };
    }

    private String quoteOf(String symbol) {
        return symbols.find(symbol).map(m -> m.getQuoteCurrencyName()).orElse(null);
    }
}
