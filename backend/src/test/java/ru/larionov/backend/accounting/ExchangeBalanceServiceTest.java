package ru.larionov.backend.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.account.Position;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.money.FxRate;
import ru.larionov.backend.money.FxRateService;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.AccountCashService;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeRuntimeService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сколько стоит подключение.
 *
 * Инцидент 08.08.2026 на Poloniex: на счёте 112.30 USDT и 84.31 DOGE, приложение
 * же показывало «свободно в портфеле −48.81 USDT» при розданных 125. Ошибок было
 * две, и каждой хватало в одиночку: деньги под выставленными покупками не считались
 * деньгами вовсе, а монета не считалась ничем.
 */
class ExchangeBalanceServiceTest {

    private final UUID connectionId = UUID.randomUUID();
    private final InstrumentId dogeUsdt = new InstrumentId("POLONIEX:DOGE_USDT", null);

    private AccountCashService accountCash;
    private LastPriceCache prices;
    private FxRateService fx;
    private MarketDataApi marketData;
    private InstrumentsApi instruments;
    private ExchangeBalanceService service;

    @BeforeEach
    void setUp() {
        accountCash = mock(AccountCashService.class);
        prices = new LastPriceCache();
        fx = mock(FxRateService.class);
        marketData = mock(MarketDataApi.class);
        instruments = mock(InstrumentsApi.class);

        ExchangeRuntimeService runtime = mock(ExchangeRuntimeService.class);
        ExchangeHandler handler = mock(ExchangeHandler.class);
        ExchangeClient client = mock(ExchangeClient.class);
        when(runtime.get(connectionId)).thenReturn(Optional.of(handler));
        when(handler.client()).thenReturn(client);
        when(client.marketData()).thenReturn(marketData);
        when(client.instruments()).thenReturn(instruments);
        when(client.meta()).thenReturn(new ExchangeMeta(ExchangeType.POLONIEX,
                false, true, true, false, false, List.of("USDT"), true));

        when(instruments.list(any())).thenReturn(List.of(new InstrumentBrief(
                dogeUsdt, InstrumentKind.CRYPTO_SPOT, MarketSegment.SPOT,
                "DOGE_USDT", "Dogecoin", null, "POLONIEX", "USDT")));
        when(fx.rate(any(), any())).thenReturn(Optional.empty());
        when(accountCash.positions(connectionId)).thenReturn(List.of());

        service = new ExchangeBalanceService(runtime, accountCash, prices, fx);
    }

    /**
     * Главный случай инцидента. 112.30 USDT из скриншота — это ВЕСЬ остаток:
     * 70.32 свободных и 41.98 под выставленными покупками. Монета — товар,
     * и в баланс входит по цене пары.
     */
    @Test
    void walletIsCashUnderOrdersIncludedPlusCoinsAtMarket() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of(
                "USDT", new BigDecimal("112.30074686"),
                "DOGE", new BigDecimal("84.311432")));
        when(marketData.getLastPrice(dogeUsdt)).thenReturn(
                new LastPrice(dogeUsdt, new Price(new BigDecimal("0.070976"), "USDT"), Instant.now()));

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.baseCurrency()).isEqualTo("USDT");
        assertThat(wallet.cash()).isEqualByComparingTo("112.30074686");
        assertThat(wallet.assets()).isEqualByComparingTo("5.984088197632");
        // Те самые 118 долларов, которые видно на бирже глазами.
        assertThat(wallet.total()).isEqualByComparingTo("118.284835057632");
        assertThat(wallet.incomplete()).isFalse();
    }

    /** Расчётная валюта из настроек важнее той, что назвала биржа. */
    @Test
    void configuredBaseCurrencyWins() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of(
                "USDT", new BigDecimal("100"),
                "DOGE", new BigDecimal("10")));
        when(marketData.getLastPrice(any())).thenReturn(
                new LastPrice(dogeUsdt, new Price(new BigDecimal("0.07"), "USDT"), Instant.now()));

        assertThat(service.baseCurrency(connectionId, "usdt")).isEqualTo("USDT");
        assertThat(service.baseCurrency(connectionId, null)).isEqualTo("USDT");
    }

    /**
     * Цена, уже полученная ботом из стрима, экономит запрос: боты сидят на стриме
     * постоянно, а список подключений опрашивается каждые несколько секунд.
     */
    @Test
    void priceFromBotStreamCostsNoRequest() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of(
                "USDT", new BigDecimal("100"),
                "DOGE", new BigDecimal("1000")));
        prices.put(UUID.randomUUID(), dogeUsdt.primary(), new BigDecimal("0.08"), Instant.now());

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.assets()).isEqualByComparingTo("80");
        verify(marketData, never()).getLastPrice(any());
    }

    /**
     * Неоценённая монета не должна ни завышать баланс, ни молча его занижать:
     * в сумму она не идёт, но пометка о неполноте остаётся.
     */
    @Test
    void coinWithoutAPriceIsReportedRatherThanGuessed() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of(
                "USDT", new BigDecimal("100"),
                "HTX", new BigDecimal("8337.72250759")));
        when(instruments.list(any())).thenReturn(List.of());

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.total()).isEqualByComparingTo("100");
        assertThat(wallet.incomplete()).isTrue();
        assertThat(wallet.assetsByCurrency()).containsKey("HTX");
    }

    /** Бумаги брокера приходят отдельным списком и уже со своей ценой. */
    @Test
    void brokerPositionsAreValuedByTheirOwnPrice() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of("USDT", new BigDecimal("100")));
        when(accountCash.positions(connectionId)).thenReturn(List.of(new Position(
                new InstrumentId("uid-magn", null), new BigDecimal("20"),
                new BigDecimal("21.5"), new BigDecimal("21.73"), null)));

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.assets()).isEqualByComparingTo("434.60");
        assertThat(wallet.total()).isEqualByComparingTo("534.60");
    }

    /** Другая РАСЧЁТНАЯ валюта переводится курсом: биржевой пары для неё нет. */
    @Test
    void anotherCashCurrencyGoesThroughTheFxRate() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of(
                "USDT", new BigDecimal("100"),
                "RUB", new BigDecimal("900")));
        when(instruments.list(any())).thenReturn(List.of());
        when(fx.rate("RUB", "USDT")).thenReturn(Optional.of(
                new FxRate("RUB", "USDT", new BigDecimal("0.01"), "CBR", Instant.now())));

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.total()).isEqualByComparingTo("109");
        assertThat(wallet.incomplete()).isFalse();
    }

    /** Недоступная биржа — это «неизвестно», а не ноль. */
    @Test
    void unreachableExchangeYieldsUnknownRatherThanZero() {
        when(accountCash.totalByCurrency(connectionId)).thenReturn(Map.of());

        var wallet = service.balance(connectionId, null);

        assertThat(wallet.known()).isFalse();
        assertThat(wallet.total()).isNull();
    }
}
