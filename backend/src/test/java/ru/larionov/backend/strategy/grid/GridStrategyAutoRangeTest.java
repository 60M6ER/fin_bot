package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class GridStrategyAutoRangeTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private StrategyContext ctx;
    private ExchangeClient exchange;
    private MarketDataApi marketData;
    private ExecutionGateway gateway;
    private AtomicReference<GridStrategyState> saved;
    private AtomicReference<Instant> currentTime;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        saved = new AtomicReference<>();
        currentTime = new AtomicReference<>(now);

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(new TradingConstraints(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, 1, null, null, null, null));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.loadState(GridStrategyState.class)).thenAnswer(__ -> Optional.ofNullable(saved.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(ctx).saveState(any());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(exchange.calendar()).thenThrow(new UnsupportedOperationException("calendar disabled in unit test"));
        when(marketData.getLastPrice(instrumentId))
                .thenReturn(new LastPrice(instrumentId, new Price(new BigDecimal("100"), "rub"), now));
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(gateway.openOrders(botId)).thenReturn(List.of());
    }

    @Test
    void initialRangeIsCalculatedSavedAndExposed() {
        GridStrategy strategy = new GridStrategy(autoConfig());

        strategy.onStart(ctx, reconciled("0"));

        assertThat(saved.get()).isNotNull();
        assertThat(saved.get().activeRange().origin()).isEqualTo(GridRange.Origin.ATR_INITIAL);
        var snapshot = strategy.snapshot().orElseThrow();
        assertThat(snapshot.lowerPrice()).isEqualByComparingTo("92");
        assertThat(snapshot.upperPrice()).isEqualByComparingTo("108");
        assertThat(snapshot.ladderPrices()).hasSize(5);
    }

    @Test
    void restartRestoresRangeWithoutRequestingCandlesAgain() {
        GridStrategy first = new GridStrategy(autoConfig());
        first.onStart(ctx, reconciled("0"));

        GridStrategy restarted = new GridStrategy(autoConfig());
        assertThatCode(() -> restarted.onStart(ctx, reconciled("1")))
                .doesNotThrowAnyException();

        assertThat(restarted.snapshot().orElseThrow().lowerPrice()).isEqualByComparingTo("92");
        verify(marketData, times(1)).getLastPrice(instrumentId);
        verify(marketData, times(1)).getCandles(any(), any());
    }

    @Test
    void missingCheckpointWithOpenPositionRefusesToStart() {
        assertThatThrownBy(() -> new GridStrategy(autoConfig()).onStart(ctx, reconciled("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("сохранённый диапазон отсутствует");
    }

    @Test
    void confirmedUpperBreakoutCancelsOnlyBuysAndReturnInsideAbortsReplacement() {
        BotOrderView buy = mock(BotOrderView.class);
        UUID buyId = UUID.randomUUID();
        when(buy.side()).thenReturn(OrderSide.BUY);
        when(buy.id()).thenReturn(buyId);
        when(buy.clientOrderId()).thenReturn("buy-1");
        when(gateway.openOrders(botId)).thenReturn(List.of(buy));

        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("1"));

        strategy.onPrice(lastPrice("111"));
        assertThat(strategy.snapshot().orElseThrow().buyingStopped()).isFalse();

        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().awaitingUpperReplacement()).isTrue();
        assertThat(strategy.snapshot().orElseThrow().awaitingReplacement()).isTrue();
        verify(gateway).cancel(any(), org.mockito.ArgumentMatchers.eq(buyId));

        currentTime.set(now.plusSeconds(12));
        strategy.onPrice(lastPrice("107"));

        assertThat(saved.get().awaitingUpperReplacement()).isFalse();
        assertThat(strategy.snapshot().orElseThrow().buyingStopped()).isFalse();
        assertThat(strategy.snapshot().orElseThrow().generation()).isEqualTo(1);
        verify(ctx, never()).ledgerMarker(any(), any());
    }

    @Test
    void zeroPositionReplacesRangeUpAndPersistsNextGeneration() {
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        GridStrategyState state = saved.get();
        assertThat(state.generation()).isEqualTo(2);
        assertThat(state.awaitingUpperReplacement()).isFalse();
        assertThat(state.activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_UP);
        assertThat(state.activeRange().lower()).isEqualByComparingTo("103");
        assertThat(state.activeRange().upper()).isEqualByComparingTo("119");
        verify(ctx).ledgerMarker(any(), org.mockito.ArgumentMatchers.contains("поколение 2"));
        verify(marketData, times(2)).getCandles(any(), any());
        verify(marketData, times(1)).getLastPrice(instrumentId);
    }

    @Test
    void watchdogCompletesConfirmationWithoutAnotherPriceEvent() {
        when(gateway.reconcile(any())).thenReturn(reconciled("0"));
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onTick();

        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_UP);
    }

    @Test
    void drainingCheckpointSurvivesRestartAndCompletesAfterReconcile() {
        GridRange range = new GridRange(new BigDecimal("92"), new BigDecimal("108"), 4,
                GridRange.Origin.ATR_INITIAL, now);
        saved.set(new GridStrategyState(range, 1, true, null));

        GridStrategy restarted = new GridStrategy(replaceUpperConfig());
        restarted.onStart(ctx, reconciled("1"));
        restarted.onReconcile(reconciled("1"));
        restarted.onPrice(lastPrice("111"));

        assertThat(restarted.snapshot().orElseThrow().awaitingReplacement()).isTrue();
        verify(marketData, never()).getCandles(any(), any());

        restarted.onReconcile(reconciled("0"));

        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_UP);
    }

    @Test
    void positionMismatchPreventsBreakoutTransition() {
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        // Две сверки подряд: расхождение считается настоящим только с подтверждения.
        strategy.onReconcile(new ReconcileResult(
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ONE));
        strategy.onReconcile(new ReconcileResult(
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ONE));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(20));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().generation()).isEqualTo(1);
        assertThat(saved.get().awaitingUpperReplacement()).isFalse();
        assertThat(strategy.snapshot().orElseThrow().buyingStopped()).isFalse();
        verify(marketData, times(1)).getCandles(any(), any());
    }

    /**
     * Одиночное расхождение — это почти всегда гонка расчётов у брокера: журнал уже
     * знает об исполнении, а расчётная позиция ещё нет. За торговый день такие
     * «расхождения» останавливали торговлю полтора десятка раз, каждый раз снимаясь
     * сами на следующей же сверке.
     */
    @Test
    void singleTransientMismatchDoesNotBlockTrading() {
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(new ReconcileResult(
                List.of(), BigDecimal.ONE, BigDecimal.ZERO, 0, 0, BigDecimal.ONE));
        // Следующая сверка расхождения уже не видит — торговля не должна была вставать.
        strategy.onReconcile(reconciled("1"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().awaitingUpperReplacement()).isTrue();
    }

    @Test
    void rejectedCandidateKeepsOldRangeAndStopsBuying() {
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, 1, new BigDecimal("400"), null, null, null));
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().generation()).isEqualTo(1);
        assertThat(saved.get().activeRange().lower()).isEqualByComparingTo("92");
        assertThat(saved.get().awaitingUpperReplacement()).isTrue();
        assertThat(strategy.snapshot().orElseThrow().halted()).isTrue();
        verify(ctx, never()).ledgerMarker(any(), any());
    }

    private GridConfig autoConfig() {
        return new GridConfig(
                null, null, 4, 1L, 4,
                GridConfig.RangeExitAction.STOP_BUYING, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 300, new BigDecimal("0.002"),
                1200, 0, null,
                null, null, null);
    }

    private GridConfig replaceUpperConfig() {
        return new GridConfig(
                null, null, 4, 1L, 4,
                GridConfig.RangeExitAction.STOP_BUYING, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.REPLACE_UPPER, 10, new BigDecimal("0.002"),
                1200, 0, null,
                null, null, null);
    }

    private LastPrice lastPrice(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }

    private ReconcileResult reconciled(String position) {
        return new ReconcileResult(List.of(), new BigDecimal(position), BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO);
    }

    private List<Candle> candles() {
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
