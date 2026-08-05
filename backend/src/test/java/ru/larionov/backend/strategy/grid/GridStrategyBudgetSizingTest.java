package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Бюджетный сайзинг глазами работающей стратегии.
 *
 * Самое ценное здесь — инвариант стабильности: у бота с реинвестированием прибыли
 * размер заявки НЕ должен меняться между перестройками сетки. Иначе объём поехал бы
 * посреди жизни сетки — между покупкой и её ещё не выставленной встречной продажей.
 */
class GridStrategyBudgetSizingTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<BigDecimal> realizedPnl;
    private AtomicReference<GridStrategyState> saved;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        realizedPnl = new AtomicReference<>(BigDecimal.ZERO);
        saved = new AtomicReference<>();

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
        when(ctx.realizedPnl()).thenAnswer(__ -> realizedPnl.get());
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
        when(gateway.recentOrders(botId)).thenReturn(List.of());
    }

    // ==============================
    // РАЗМЕР ЗАЯВКИ
    // ==============================

    @Test
    void perLevelPlacesDifferentLotCountsPerLevel() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.PER_LEVEL, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        Map<Integer, Long> placed = placedBuyLots();
        assertThat(placed).isNotEmpty();

        GridSizing expected = expectedSizing(strategy, GridConfig.SizingMode.PER_LEVEL,
                new BigDecimal("20000"));
        placed.forEach((level, lots) ->
                assertThat(lots).as("уровень %d", level).isEqualTo(expected.lotsAt(level)));

        // Дешёвые уровни внизу получают больше лотов, чем дорогие вверху.
        assertThat(expected.lotsAt(0)).isGreaterThan(expected.lotsAt(expected.lotsByLevel().size() - 1));
    }

    @Test
    void uniformPlacesTheSameLotCountOnEveryLevel() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        assertThat(placedBuyLots().values()).isNotEmpty().containsOnly(
                expectedSizing(strategy, GridConfig.SizingMode.UNIFORM, new BigDecimal("20000")).lotsAt(0));
    }

    @Test
    void snapshotExposesSizingForTheUi() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.PER_LEVEL, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        var snapshot = strategy.snapshot().orElseThrow();
        assertThat(snapshot.sizingMode()).isEqualTo("PER_LEVEL");
        assertThat(snapshot.workingBudget()).isEqualByComparingTo("20000");
        assertThat(snapshot.lotsByLevel()).isNotEmpty();
        assertThat(snapshot.worstCaseNotional()).isLessThanOrEqualTo(new BigDecimal("20000"));
    }

    // ==============================
    // ИНВАРИАНТ СТАБИЛЬНОСТИ
    // ==============================

    @Test
    void compoundingDoesNotResizeAnActiveGrid() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        startTrading(strategy);

        long before = strategy.snapshot().orElseThrow().lotsByLevel().get(0);

        // Прибыль выросла вдвое от бюджета — но сетка уже расставлена.
        realizedPnl.set(new BigDecimal("20000"));
        currentTime.set(now.plusSeconds(7200));   // заведомо больше feeRefreshSeconds
        strategy.onPrice(lastPrice("100"));
        strategy.onTick();

        assertThat(strategy.snapshot().orElseThrow().lotsByLevel().get(0))
                .as("размер заявки не должен меняться между перестройками сетки")
                .isEqualTo(before);
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("20000");
    }

    @Test
    void compoundingResizesOnRestartWhenTheGridIsRebuiltFromScratch() {
        GridStrategy first = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        startTrading(first);
        long before = first.snapshot().orElseThrow().lotsByLevel().get(0);

        // Перезапуск после заработанной прибыли: бюджет пересчитывается на onStart.
        realizedPnl.set(new BigDecimal("20000"));
        GridStrategy restarted = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        restarted.onStart(ctx, reconciled("0"));

        assertThat(restarted.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("40000");
        assertThat(restarted.snapshot().orElseThrow().lotsByLevel().get(0)).isGreaterThan(before);
    }

    @Test
    void withdrawIgnoresProfitEntirely() {
        GridStrategy first = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(first);
        long before = first.snapshot().orElseThrow().lotsByLevel().get(0);

        realizedPnl.set(new BigDecimal("20000"));
        GridStrategy restarted = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        restarted.onStart(ctx, reconciled("0"));

        assertThat(restarted.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("20000");
        assertThat(restarted.snapshot().orElseThrow().lotsByLevel().get(0)).isEqualTo(before);
    }

    // ==============================
    // СОВМЕСТИМОСТЬ
    // ==============================

    @Test
    void fixedLotsPlacesExactlyTheConfiguredSize() {
        GridConfig cfg = new GridConfig(
                null, null, 4, 5L, 4, null, null, null, true,
                true, null, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 0, null,
                null, null, null);

        GridStrategy strategy = new GridStrategy(cfg);
        startTrading(strategy);

        // Старое поведение до байта: одинаково 5 лотов, бюджет ни при чём.
        assertThat(placedBuyLots().values()).isNotEmpty().containsOnly(5L);
        assertThat(strategy.snapshot().orElseThrow().sizingMode()).isEqualTo("FIXED_LOTS");
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isNull();
    }

    // ==============================
    // HELPERS
    // ==============================

    private void startTrading(GridStrategy strategy) {
        strategy.onStart(ctx, reconciled("0"));
        strategy.onTradingStatus(new ru.larionov.backend.exchange.api.model.market.TradingStatusEvent(
                instrumentId, true, true, "NORMAL_TRADING", now));
        strategy.onPrice(lastPrice("100"));
    }

    /** Что именно ушло в гейтвей: уровень покупки → число лотов. */
    private Map<Integer, Long> placedBuyLots() {
        ArgumentCaptor<PlaceIntent> captor = ArgumentCaptor.forClass(PlaceIntent.class);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.atLeastOnce())
                .placeLimit(any(), captor.capture());
        return captor.getAllValues().stream()
                .filter(intent -> intent.side() == OrderSide.BUY && intent.gridLevel() != null)
                .collect(Collectors.toMap(PlaceIntent::gridLevel, PlaceIntent::lots, (a, b) -> a));
    }

    private GridSizing expectedSizing(GridStrategy strategy, GridConfig.SizingMode mode, BigDecimal budget) {
        var snapshot = strategy.snapshot().orElseThrow();
        GridLadder ladder = GridLadder.build(
                GridRange.manual(new GridConfig(snapshot.lowerPrice(), snapshot.upperPrice(),
                        snapshot.ladderPrices().size() - 1, 1L, 10, null, null, true), null),
                new BigDecimal("0.01"));
        return GridSizing.fromBudget(config(budget.toPlainString(), mode, GridConfig.ProfitPolicy.WITHDRAW),
                ladder, 1, budget);
    }

    private GridConfig config(String budget, GridConfig.SizingMode mode, GridConfig.ProfitPolicy policy) {
        return new GridConfig(
                null, null, 4, null, 4, null, null, null, true,
                true, null, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 0, null,
                new BigDecimal(budget), mode, policy);
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
