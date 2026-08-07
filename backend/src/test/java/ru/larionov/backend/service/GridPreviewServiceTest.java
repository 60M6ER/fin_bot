package ru.larionov.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.dto.GridPreviewDto;
import ru.larionov.backend.dto.GridPreviewRequest;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.FeesApi;
import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.grid.GridConfig;
import ru.larionov.backend.strategy.grid.GridRange;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GridPreviewServiceTest {

    private static final String JSON = "{\"instrumentUid\":\"uid-1\"}";

    private final UUID connectionId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final AccountId accountId = new AccountId("account-1");

    private ExchangeRuntimeService runtimeService;
    private ObjectMapper objectMapper;
    private ExchangeHandler handler;
    private ExchangeClient client;
    private InstrumentsApi instruments;
    private FeesApi fees;
    private MarketDataApi marketData;
    private GridPreviewService service;

    @BeforeEach
    void setUp() throws Exception {
        runtimeService = mock(ExchangeRuntimeService.class);
        objectMapper = mock(ObjectMapper.class);
        handler = mock(ExchangeHandler.class);
        client = mock(ExchangeClient.class);
        instruments = mock(InstrumentsApi.class);
        fees = mock(FeesApi.class);
        marketData = mock(MarketDataApi.class);
        service = new GridPreviewService(runtimeService, new AccountCashService(runtimeService), objectMapper);

        when(runtimeService.get(connectionId)).thenReturn(Optional.of(handler));
        when(handler.client()).thenReturn(client);
        when(handler.tradingAccountId()).thenReturn(accountId);
        when(client.instruments()).thenReturn(instruments);
        when(client.fees()).thenReturn(fees);
        when(client.marketData()).thenReturn(marketData);
        when(instruments.getConstraints(instrumentId))
                .thenReturn(TradingConstraints.wholeLots(10, new BigDecimal("0.01"), "rub"));
        when(fees.getFeeInfo(accountId, instrumentId))
                .thenReturn(new FeeInfo(new BigDecimal("0.0004"), new BigDecimal("0.0006"),
                        new BigDecimal("0.0007"), new BigDecimal("0.0008")));
    }

    @Test
    void returnsTheSameLadderEconomicsAndCapitalAsValidator() throws Exception {
        mockConfigs(manualConfig(), new BigDecimal("20000"));

        GridPreviewDto preview = service.preview(new GridPreviewRequest(connectionId, JSON));

        assertThat(preview.ready()).isTrue();
        assertThat(preview.error()).isNull();
        assertThat(preview.rangeOrigin()).isEqualTo(GridRange.Origin.MANUAL);
        assertThat(preview.ladderPrices()).hasSize(11);
        assertThat(preview.ladderPrices().getFirst()).isEqualByComparingTo("100");
        assertThat(preview.ladderPrices().getLast()).isEqualByComparingTo("110");
        assertThat(preview.effectiveStep()).isEqualByComparingTo("1");
        assertThat(preview.buyFeePercent()).isEqualByComparingTo("0.04");
        assertThat(preview.sellFeePercent()).isEqualByComparingTo("0.06");
        assertThat(preview.roundTripFeePercent()).isEqualByComparingTo("0.10");
        assertThat(preview.worstCaseCapital()).isEqualByComparingTo("10450");
        assertThat(preview.quantityStep()).isEqualByComparingTo("10");
        assertThat(preview.priceIncrement()).isEqualByComparingTo("0.01");
    }

    @Test
    void reportsCapitalValidationAsPreviewError() throws Exception {
        mockConfigs(manualConfig(), new BigDecimal("500"));

        GridPreviewDto preview = service.preview(new GridPreviewRequest(connectionId, JSON));

        assertThat(preview.ready()).isFalse();
        assertThat(preview.error()).contains("не помещается в лимит капитала")
                .contains("10450.00");
        assertThat(preview.ladderPrices()).isEmpty();
    }

    @Test
    void calculatesAutoRangeFromTheSameMarketDataAsStrategy() throws Exception {
        GridConfig auto = new GridConfig(
                null, null, 4, new BigDecimal("10"), 4,
                GridConfig.RangeExitAction.STOP_BUYING, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 10, new BigDecimal("0.002"),
                1200, 0, null,
                null, null, null);
        mockConfigs(auto, new BigDecimal("20000"));
        when(marketData.getLastPrice(instrumentId)).thenReturn(
                new LastPrice(instrumentId, new Price(new BigDecimal("100"), "rub"), Instant.now()));
        when(marketData.getCandles(eq(instrumentId), any())).thenReturn(candles());

        GridPreviewDto preview = service.preview(new GridPreviewRequest(connectionId, JSON));

        assertThat(preview.ready()).isTrue();
        assertThat(preview.rangeOrigin()).isEqualTo(GridRange.Origin.ATR_INITIAL);
        assertThat(preview.referencePrice()).isEqualByComparingTo("100");
        assertThat(preview.atr()).isEqualByComparingTo("4");
        assertThat(preview.atrCandlesUsed()).isEqualTo(6);
        assertThat(preview.lowerPrice()).isEqualByComparingTo("92");
        assertThat(preview.upperPrice()).isEqualByComparingTo("108");
    }

    private void mockConfigs(GridConfig gridConfig, BigDecimal maxCapital) throws Exception {
        BotRuntimeConfig runtimeConfig = new BotRuntimeConfig(
                "uid-1", false, maxCapital, null, null,
                null, null, null);
        when(objectMapper.readValue(JSON, BotRuntimeConfig.class)).thenReturn(runtimeConfig);
        when(objectMapper.readValue(JSON, GridConfig.class)).thenReturn(gridConfig);
    }

    private GridConfig manualConfig() {
        return new GridConfig(
                new BigDecimal("100"), new BigDecimal("110"), 10,
                // 10 штук: у инструмента заявочная единица 10, и мельче биржа не примет.
                new BigDecimal("10"), 10, GridConfig.RangeExitAction.STOP_BUYING,
                null, 3600, true,
                false, CandleInterval.H1, 24, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 300, new BigDecimal("0.002"),
                1200, 0, null,
                null, null, null);
    }

    private List<Candle> candles() {
        Instant now = Instant.parse("2026-01-08T12:00:00Z");
        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new Candle(instrumentId,
                        new Price(new BigDecimal("100"), "rub"),
                        new Price(new BigDecimal("102"), "rub"),
                        new Price(new BigDecimal("98"), "rub"),
                        new Price(new BigDecimal("100"), "rub"),
                        BigDecimal.ONE, now.minusSeconds((6L - i) * 3600), null))
                .toList();
    }
}
