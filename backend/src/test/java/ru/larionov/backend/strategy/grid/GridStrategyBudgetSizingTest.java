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
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
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
    private MarketDataApi marketData;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<BigDecimal> realizedPnl;
    private AtomicReference<GridStrategyState> saved;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        realizedPnl = new AtomicReference<>(BigDecimal.ZERO);
        saved = new AtomicReference<>();

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null));
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
        when(marketData.getTradingStatus(instrumentId)).thenReturn(tradingStatus(true));
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
    void perLevelPlacesDifferentQuantitiesPerLevel() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.PER_LEVEL, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        Map<Integer, BigDecimal> placed = placedBuyQuantities();
        assertThat(placed).isNotEmpty();

        GridSizing expected = expectedSizing(strategy, GridConfig.SizingMode.PER_LEVEL,
                new BigDecimal("20000"));
        placed.forEach((level, quantity) ->
                assertThat(quantity).as("уровень %d", level)
                        .isEqualByComparingTo(expected.quantityAt(level)));

        // Дешёвые уровни внизу получают больше, чем дорогие вверху.
        assertThat(expected.quantityAt(0))
                .isGreaterThan(expected.quantityAt(expected.quantityByLevel().size() - 1));
    }

    @Test
    void uniformPlacesTheSameQuantityOnEveryLevel() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        assertThat(placedBuyQuantities().values()).isNotEmpty().containsOnly(
                expectedSizing(strategy, GridConfig.SizingMode.UNIFORM,
                        new BigDecimal("20000")).quantityAt(0));
    }

    @Test
    void snapshotExposesSizingForTheUi() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.PER_LEVEL, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        var snapshot = strategy.snapshot().orElseThrow();
        assertThat(snapshot.sizingMode()).isEqualTo("PER_LEVEL");
        assertThat(snapshot.workingBudget()).isEqualByComparingTo("20000");
        assertThat(snapshot.quantityByLevel()).isNotEmpty();
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

        BigDecimal before = strategy.snapshot().orElseThrow().quantityByLevel().get(0);

        // Прибыль выросла вдвое от бюджета — но сетка уже расставлена.
        realizedPnl.set(new BigDecimal("20000"));
        currentTime.set(now.plusSeconds(7200));   // заведомо больше feeRefreshSeconds
        strategy.onPrice(lastPrice("100"));
        strategy.onTick();

        assertThat(strategy.snapshot().orElseThrow().quantityByLevel().get(0))
                .as("размер заявки не должен меняться между перестройками сетки")
                .isEqualByComparingTo(before);
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("20000");
    }

    @Test
    void compoundingResizesOnRestartWhenTheGridIsRebuiltFromScratch() {
        GridStrategy first = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        startTrading(first);
        BigDecimal before = first.snapshot().orElseThrow().quantityByLevel().get(0);

        // Перезапуск после заработанной прибыли: бюджет пересчитывается на onStart.
        realizedPnl.set(new BigDecimal("20000"));
        GridStrategy restarted = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        restarted.onStart(ctx, reconciled("0"));

        assertThat(restarted.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("40000");
        assertThat(restarted.snapshot().orElseThrow().quantityByLevel().get(0)).isGreaterThan(before);
    }

    @Test
    void withdrawIgnoresProfitEntirely() {
        GridStrategy first = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(first);
        BigDecimal before = first.snapshot().orElseThrow().quantityByLevel().get(0);

        realizedPnl.set(new BigDecimal("20000"));
        GridStrategy restarted = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        restarted.onStart(ctx, reconciled("0"));

        assertThat(restarted.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("20000");
        assertThat(restarted.snapshot().orElseThrow().quantityByLevel().get(0)).isEqualByComparingTo(before);
    }

    // ==============================
    // СОВМЕСТИМОСТЬ
    // ==============================

    @Test
    void fixedQuantityPlacesExactlyTheConfiguredSize() {
        GridConfig cfg = new GridConfig(
                null, null, 4, new BigDecimal("5"), 4, null, null, null, true,
                true, null, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 0, null,
                null, null, null);

        GridStrategy strategy = new GridStrategy(cfg);
        startTrading(strategy);

        // Старое поведение до байта: одинаково 5 штук, бюджет ни при чём.
        assertThat(placedBuyQuantities().values()).isNotEmpty()
                .allSatisfy(q -> assertThat(q).isEqualByComparingTo("5"));
        assertThat(strategy.snapshot().orElseThrow().sizingMode()).isEqualTo("FIXED_QUANTITY");
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isNull();
    }

    // ==============================
    // ТОРГОВАЯ СЕССИЯ
    // ==============================

    /**
     * Регрессия: бот, поднятый при закрытой бирже, не должен выставить НИ ОДНОЙ заявки.
     *
     * Раньше признак «торги идут» брался из календаря площадок, причём запрос шёл без
     * указания биржи и признак выставлялся, если открыта ХОТЬ ОДНА площадка из ответа.
     * Ночью бот считал сессию открытой и до утра долбился заявками, ловя отказ на каждом тике.
     */
    @Test
    void placesNothingWhileTheExchangeRejectsLimitOrders() {
        when(marketData.getTradingStatus(instrumentId)).thenReturn(tradingStatus(false));

        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        // Тики и цены не должны ничего менять, пока биржа не открылась.
        currentTime.set(now.plusSeconds(120));
        strategy.onTick();
        strategy.onPrice(lastPrice("100"));

        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).placeLimit(any(), any());
    }

    @Test
    void startsPlacingAsSoonAsTheSessionOpens() {
        when(marketData.getTradingStatus(instrumentId)).thenReturn(tradingStatus(false));

        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).placeLimit(any(), any());

        // Стрим сообщил об открытии — вот теперь можно.
        strategy.onTradingStatus(tradingStatus(true));

        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.atLeastOnce()).placeLimit(any(), any());
    }

    /**
     * Если стрим потеряет событие открытия сессии, бот обязан заметить это сам.
     * Иначе поднятый ночью бот промолчал бы весь торговый день.
     */
    @Test
    void noticesTheSessionOpeningEvenIfTheStreamMissesIt() {
        when(marketData.getTradingStatus(instrumentId)).thenReturn(tradingStatus(false));

        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).placeLimit(any(), any());

        // Биржа открылась, но события из стрима так и не пришло.
        when(marketData.getTradingStatus(instrumentId)).thenReturn(tradingStatus(true));
        currentTime.set(now.plusSeconds(60));
        strategy.onTick();

        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.atLeastOnce()).placeLimit(any(), any());
    }

    /** Пока сессия идёт, статус переспрашивать незачем — это лишний запрос на каждый тик. */
    @Test
    void doesNotRepollTheStatusWhileTheSessionIsOpen() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        currentTime.set(now.plusSeconds(60));
        strategy.onTick();
        currentTime.set(now.plusSeconds(120));
        strategy.onTick();

        // Ровно один вызов — тот, что при старте.
        org.mockito.Mockito.verify(marketData, org.mockito.Mockito.times(1))
                .getTradingStatus(instrumentId);
    }

    // ==============================
    // ЦЕНА ЗАПУСКА
    // ==============================

    /**
     * При выводе прибыли реализованный P/L в бюджете не участвует — значит и спрашивать
     * его нельзя: это полная загрузка денежной книги с ремонтным проходом, на критическом
     * пути запуска, для каждого бота по очереди.
     */
    @Test
    void doesNotLoadTheLedgerOnStartWhenProfitIsWithdrawn() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        startTrading(strategy);

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.never()).realizedPnl();
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("20000");
    }

    /** А при реинвестировании — обязан: без него бюджет посчитается неверно. */
    @Test
    void doesLoadTheLedgerOnStartWhenProfitIsReinvested() {
        realizedPnl.set(new BigDecimal("1500"));

        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.COMPOUND));
        startTrading(strategy);

        org.mockito.Mockito.verify(ctx, org.mockito.Mockito.atLeastOnce()).realizedPnl();
        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("21500");
    }

    /** Сид обязан спрашивать инструмент, а не календарь площадок. */
    @Test
    void seedsSessionStateFromTheInstrumentNotTheVenueCalendar() {
        GridStrategy strategy = new GridStrategy(config(
                "20000", GridConfig.SizingMode.UNIFORM, GridConfig.ProfitPolicy.WITHDRAW));
        strategy.onStart(ctx, reconciled("0"));

        org.mockito.Mockito.verify(marketData).getTradingStatus(instrumentId);
        // ctx.exchange().calendar() замокан на выброс — если бы сид его дёрнул, старт бы упал.
    }

    // ==============================
    // HELPERS
    // ==============================

    private void startTrading(GridStrategy strategy) {
        strategy.onStart(ctx, reconciled("0"));
        strategy.onPrice(lastPrice("100"));
    }

    private TradingStatusEvent tradingStatus(boolean limitOrdersAvailable) {
        return new TradingStatusEvent(instrumentId, limitOrdersAvailable, limitOrdersAvailable,
                limitOrdersAvailable ? "NORMAL_TRADING" : "NOT_AVAILABLE_FOR_TRADING", now);
    }

    /** Что именно ушло в гейтвей: уровень покупки → количество. */
    private Map<Integer, BigDecimal> placedBuyQuantities() {
        ArgumentCaptor<PlaceIntent> captor = ArgumentCaptor.forClass(PlaceIntent.class);
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.atLeastOnce())
                .placeLimit(any(), captor.capture());
        return captor.getAllValues().stream()
                .filter(intent -> intent.side() == OrderSide.BUY && intent.gridLevel() != null)
                .collect(Collectors.toMap(PlaceIntent::gridLevel, PlaceIntent::quantity, (a, b) -> a));
    }

    private GridSizing expectedSizing(GridStrategy strategy, GridConfig.SizingMode mode, BigDecimal budget) {
        var snapshot = strategy.snapshot().orElseThrow();
        GridLadder ladder = GridLadder.build(
                GridRange.manual(new GridConfig(snapshot.lowerPrice(), snapshot.upperPrice(),
                        snapshot.ladderPrices().size() - 1, BigDecimal.ONE, 10, null, null, true), null),
                new BigDecimal("0.01"));
        return GridSizing.fromBudget(config(budget.toPlainString(), mode, GridConfig.ProfitPolicy.WITHDRAW),
                ladder, BigDecimal.ONE, budget);
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
