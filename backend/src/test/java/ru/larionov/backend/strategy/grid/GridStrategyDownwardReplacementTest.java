package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GridStrategyDownwardReplacementTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-down", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private StrategyContext ctx;
    private ExchangeClient exchange;
    private MarketDataApi marketData;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<BigDecimal> position;
    private AtomicReference<BigDecimal> bid;
    private AtomicReference<BigDecimal> realizedPnl;
    private AtomicReference<Inventory> inventory;
    private AtomicReference<GridStrategyState> saved;
    private List<BotOrderView> openOrders;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        position = new AtomicReference<>(BigDecimal.ZERO);
        bid = new AtomicReference<>(new BigDecimal("89"));
        realizedPnl = new AtomicReference<>(BigDecimal.ZERO);
        inventory = new AtomicReference<>(Inventory.empty());
        saved = new AtomicReference<>();
        openOrders = new ArrayList<>();

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.botId()).thenReturn(botId);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.inventory()).thenAnswer(__ -> inventory.get());
        when(ctx.realizedPnl()).thenAnswer(__ -> realizedPnl.get());
        when(ctx.loadState(GridStrategyState.class)).thenAnswer(__ -> Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(ctx).saveState(any());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) ->
                new FeeInfo(new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        // Статус берётся по инструменту, а не из календаря площадок: календарь не знает,
        // примет ли биржа лимитную заявку именно по этой бумаге.
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", Instant.now()));
        when(marketData.getLastPrice(instrumentId)).thenReturn(lastPrice("100"));
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(marketData.getOrderBook(instrumentId, 1)).thenAnswer(__ -> orderBook(bid.get()));

        when(gateway.openOrders(botId)).thenAnswer(__ -> List.copyOf(openOrders));
        when(gateway.recentOrders(botId)).thenReturn(List.of());
        when(gateway.reconcile(any())).thenAnswer(__ -> reconciled(position.get()));
        doAnswer(__ -> {
            int count = openOrders.size();
            openOrders.clear();
            return count;
        }).when(gateway).cancelAll(any());
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(1);
            openOrders.removeIf(order -> order.id().equals(id));
            return null;
        }).when(gateway).cancel(any(), any());
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            BotOrderView order = order(intent);
            openOrders.add(order);
            return order;
        });
    }

    @Test
    void liquidatesAtBestBidAndActivatesValidatedLowerRange() {
        GridStrategy strategy = start(config("50", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));

        confirmLowerBreakout(strategy);

        ArgumentCaptor<PlaceIntent> intent = ArgumentCaptor.forClass(PlaceIntent.class);
        verify(gateway, atLeastOnce()).placeLimit(any(), intent.capture());
        PlaceIntent liquidation = intent.getAllValues().get(intent.getAllValues().size() - 1);
        assertThat(liquidation.side()).isEqualTo(OrderSide.SELL);
        assertThat(liquidation.quantity()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(liquidation.limitPrice()).isEqualByComparingTo("89");
        assertThat(liquidation.gridLevel()).isNull();
        assertThat(saved.get().awaitingDownwardReplacement()).isTrue();
        assertThat(saved.get().pendingRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_DOWN);

        openOrders.clear();
        position.set(BigDecimal.ZERO);
        inventory.set(Inventory.empty());
        realizedPnl.set(new BigDecimal("-11.0445"));
        strategy.onReconcile(reconciled(BigDecimal.ZERO));

        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().downwardReplacements()).isEqualTo(1);
        assertThat(saved.get().realizedDownwardLoss()).isEqualByComparingTo("11.0445");
        assertThat(saved.get().activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_DOWN);
        assertThat(saved.get().activeRange().lower()).isEqualByComparingTo("81");
        assertThat(saved.get().activeRange().upper()).isEqualByComparingTo("97");
        verify(ctx).ledgerMarker(any(), contains("заменён вниз"));
    }

    @Test
    void refusesLiquidationBeforeCancellingWhenProjectedLossExceedsBudget() {
        GridStrategy strategy = start(config("5", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));

        confirmLowerBreakout(strategy);

        verify(gateway, never()).placeLimit(any(), any());
        verify(ctx).requestStop(contains("бюджетом убытка"));
        verify(ctx).event(eq(BotEventLevel.ERROR), eq(BotEventType.RANGE_EXIT), any());
        assertThat(saved.get().generation()).isEqualTo(1);
    }

    @Test
    void cancelsExistingBuysWhileLowerBreakoutIsBeingConfirmed() {
        GridStrategy strategy = start(config("50", 2));
        BotOrderView openBuy = order(new PlaceIntent(
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("92"), 0));
        openOrders.add(openBuy);

        strategy.onPrice(lastPrice("89"));

        verify(gateway).cancel(ctx.execution(), openBuy.id());
        assertThat(openOrders).isEmpty();
        assertThat(strategy.snapshot().orElseThrow().buyingStopped()).isTrue();
        verify(gateway, never()).cancelAll(any());
    }

    @Test
    void worseningBidStopsBeforeLossBudgetCanBeExceeded() {
        GridStrategy strategy = start(config("15", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));
        confirmLowerBreakout(strategy);

        bid.set(new BigDecimal("80"));
        currentTime.set(now.plusSeconds(20));
        strategy.onTick();

        verify(ctx).requestStop(contains("бюджетом убытка"));
        assertThat(openOrders).isEmpty();
        assertThat(strategy.snapshot().orElseThrow().halted()).isTrue();
    }

    @Test
    void restartContinuesSavedLiquidationInsteadOfRebuildingOldGrid() {
        GridRange active = new GridRange(new BigDecimal("92"), new BigDecimal("108"), 4,
                GridRange.Origin.ATR_INITIAL, now);
        GridRange pending = new GridRange(new BigDecimal("81"), new BigDecimal("97"), 4,
                GridRange.Origin.ATR_REPLACED_DOWN, now.plusSeconds(10));
        saved.set(new GridStrategyState(active, 1, false, null,
                true, pending, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));

        GridStrategy restarted = new GridStrategy(config("50", 2));
        restarted.onStart(ctx, reconciled(position.get()));
        restarted.onReconcile(reconciled(position.get()));
        restarted.onTick();

        verify(gateway).placeLimit(any(), argThat(i -> i.side() == OrderSide.SELL
                && i.gridLevel() == null && i.limitPrice().compareTo(new BigDecimal("89")) == 0));
        assertThat(restarted.snapshot().orElseThrow().replacementDirection()).isEqualTo("DOWN");
        // Старая сетка не перестраивается: за свечами не ходим.
        verify(marketData, never()).getCandles(any(), any());
        // Цену при рестарте берём у биржи — стрим присылает её только при сделке.
        verify(marketData).getLastPrice(instrumentId);
    }

    @Test
    void persistedReplacementCountStopsNextDownwardMoveBeforeTrading() {
        GridRange active = new GridRange(new BigDecimal("92"), new BigDecimal("108"), 4,
                GridRange.Origin.ATR_REPLACED_DOWN, now);
        saved.set(new GridStrategyState(active, 2, false, now.minusSeconds(7200),
                false, null, 1, new BigDecimal("10"), null));
        GridStrategy strategy = new GridStrategy(config("50", 1));
        strategy.onStart(ctx, reconciled(BigDecimal.ZERO));
        strategy.onReconcile(reconciled(BigDecimal.ZERO));
        clearInvocations(gateway, marketData);

        confirmLowerBreakout(strategy);

        verify(ctx).requestStop(contains("Исчерпан лимит перестановок вниз"));
        verify(gateway).cancelAll(any());
        verify(gateway, never()).placeLimit(any(), any());
        verify(marketData, never()).getCandles(any(), any());
        assertThat(saved.get().downwardReplacements()).isEqualTo(1);
        assertThat(saved.get().realizedDownwardLoss()).isEqualByComparingTo("10");
    }

    @Test
    void doesNotTouchOrdersWhenLiquidationCheckpointCannotBeSaved() {
        GridStrategy strategy = start(config("50", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));
        doAnswer(invocation -> {
            GridStrategyState state = invocation.getArgument(0);
            if (state.awaitingDownwardReplacement()) {
                throw new IllegalStateException("state storage unavailable");
            }
            saved.set(state);
            return null;
        }).when(ctx).saveState(any());
        clearInvocations(gateway);

        confirmLowerBreakout(strategy);

        verify(gateway, never()).cancelAll(any());
        verify(gateway, never()).cancel(any(), any());
        verify(gateway, never()).placeLimit(any(), any());
        verify(ctx).event(eq(BotEventType.RISK_BLOCKED), contains("сохранить начало ликвидации"));
        assertThat(strategy.snapshot().orElseThrow().halted()).isTrue();
        assertThat(saved.get().awaitingDownwardReplacement()).isFalse();
    }

    @Test
    void doesNotPlaceLiquidationUntilEveryOldOrderIsCancelled() {
        GridStrategy strategy = start(config("50", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));
        openOrders.add(order(new PlaceIntent(
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("92"), 0)));
        doThrow(new IllegalStateException("cancel unavailable"))
                .when(gateway).cancel(any(), any());
        doThrow(new IllegalStateException("cancel all unavailable"))
                .when(gateway).cancelAll(any());
        clearInvocations(gateway);

        confirmLowerBreakout(strategy);

        verify(gateway, never()).placeLimit(any(), any());
        assertThat(openOrders).hasSize(1);
        assertThat(saved.get().awaitingDownwardReplacement()).isTrue();
        assertThat(strategy.snapshot().orElseThrow().replacementDirection()).isEqualTo("DOWN");
    }

    /**
     * Регрессия на состояние, в которое бот попал сразу после того, как ликвидация
     * впервые заработала.
     *
     * Принудительная продажа закрывает позицию целиком одной заявкой без уровня —
     * сопоставить её конкретному уровню нельзя, она закрывает сразу несколько.
     * Поуровневый учёт её поэтому не видел и продолжал считать старые покупки
     * непроданными. В сетке нового поколения эти уровни оказывались занятыми
     * несуществующей позицией: продажу на них запрещал риск-контроль (по журналу
     * куплено ноль), а покупку — сама эта запись. В логе это шло как
     * «Продажа вышла бы за пределы позиции ... куплено всего 0» каждый тик.
     */
    @Test
    void levelsOfLiquidatedGenerationDoNotBlockTheNewGrid() {
        GridStrategy strategy = start(config("50", 2));
        position.set(BigDecimal.ONE);
        inventory.set(new Inventory(BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100")));
        strategy.onReconcile(reconciled(position.get()));

        // Покупка старого поколения исполнилась и была закрыта ликвидационной
        // продажей без уровня — ровно как в бою.
        when(gateway.recentOrders(botId)).thenReturn(List.of(
                executed(OrderSide.BUY, 0, 1, now),
                executed(OrderSide.SELL, null, 1, now.plusSeconds(5))));

        confirmLowerBreakout(strategy);

        openOrders.clear();
        position.set(BigDecimal.ZERO);
        inventory.set(Inventory.empty());
        currentTime.set(now.plusSeconds(30));
        clearInvocations(gateway);
        strategy.onReconcile(reconciled(BigDecimal.ZERO));

        assertThat(saved.get().generation()).isEqualTo(2);
        verify(gateway, never()).placeLimit(any(), argThat(i -> i.side() == OrderSide.SELL));
        verify(ctx, never()).event(eq(BotEventType.RISK_BLOCKED), any());
    }

    /** Уровень действительно с позицией по-прежнему должен считаться занятым. */
    @Test
    void levelHeldWithinCurrentGenerationIsStillCounted() {
        GridStrategy strategy = start(config("50", 2));
        when(gateway.recentOrders(botId)).thenReturn(List.of(
                executed(OrderSide.BUY, 0, 1, now.plusSeconds(1))));
        position.set(BigDecimal.ONE);
        strategy.onReconcile(reconciled(BigDecimal.ONE));
        clearInvocations(gateway);

        strategy.onPrice(lastPrice("100"));

        verify(gateway).placeLimit(any(), argThat(i -> i.side() == OrderSide.SELL && i.gridLevel() == 0));
    }

    private BotOrderView executed(OrderSide side, Integer gridLevel, long quantity, Instant createdAt) {
        BigDecimal q = BigDecimal.valueOf(quantity);
        return new BotOrderView(
                UUID.randomUUID(), UUID.randomUUID().toString(), "exch-1",
                side, OrderStatus.FILLED, gridLevel, OrderPurpose.GRID, q, q,
                new BigDecimal("100"), new BigDecimal("100"),
                null, false, null, null, "rub", BigDecimal.ONE,
                false, null, createdAt, createdAt);
    }

    private GridStrategy start(GridConfig config) {
        GridStrategy strategy = new GridStrategy(config);
        strategy.onStart(ctx, reconciled(BigDecimal.ZERO));
        strategy.onReconcile(reconciled(BigDecimal.ZERO));
        return strategy;
    }

    private void confirmLowerBreakout(GridStrategy strategy) {
        strategy.onPrice(lastPrice("89"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("89"));
    }

    private GridConfig config(String maxLoss, int maxReplacements) {
        return new GridConfig(
                null, null, 4, new BigDecimal("1"), 4,
                GridConfig.RangeExitAction.REPLACE_LOWER, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 10, new BigDecimal("0.002"),
                1200, maxReplacements, new BigDecimal(maxLoss),
                null, null, null);
    }

    private ReconcileResult reconciled(BigDecimal value) {
        return new ReconcileResult(List.of(), value, value, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO);
    }

    private LastPrice lastPrice(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }

    private OrderBook orderBook(BigDecimal value) {
        return new OrderBook(instrumentId, 1,
                List.of(new OrderBookLevel(new Price(value, "rub"), BigDecimal.ONE)),
                List.of(), null, null, currentTime.get());
    }

    private BotOrderView order(PlaceIntent intent) {
        return new BotOrderView(
                UUID.randomUUID(), UUID.randomUUID().toString(), null,
                intent.side(), OrderStatus.NEW, intent.gridLevel(), intent.purpose(), intent.quantity(), BigDecimal.ZERO,
                intent.limitPrice(), null, null, false, null, null, "rub", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
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
