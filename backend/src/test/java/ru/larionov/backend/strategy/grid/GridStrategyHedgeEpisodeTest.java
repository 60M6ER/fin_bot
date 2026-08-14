package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Эпизод восстановительного плеча.
 *
 * Самая денежная ветка во всём боте: здесь позиция переворачивается и становится
 * непокрытой. Поэтому проверяется не только то, что переворот происходит, но и —
 * прежде всего — все случаи, когда он ПРОИСХОДИТЬ НЕ ДОЛЖЕН и бот обязан тихо
 * вернуться к прежнему поведению, закрыв позицию с убытком.
 */
class GridStrategyHedgeEpisodeTest {

    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private UUID botId;
    private StrategyContext ctx;
    private ExecutionGateway gateway;
    /** Поле, а не локальная переменная: тесты доопределяют цену уже после старта. */
    private MarketDataApi marketData;
    private AtomicReference<Instant> clockNow;
    private AtomicReference<GridStrategyState> saved;

    @BeforeEach
    void setUp() {
        botId = UUID.randomUUID();
        clockNow = new AtomicReference<>(now);
        saved = new AtomicReference<>();
    }

    @Test
    @DisplayName("подтверждённый пробой вниз переворачивает позицию, а не закрывает её")
    void confirmedBreakoutFlipsThePosition() {
        GridStrategy strategy = startedWithPosition(true, true, true);

        breakDownAndConfirm(strategy);

        PlaceIntent flip = lastIntentWithPurpose(OrderPurpose.HEDGE);
        assertThat(flip).as("переворот обязан быть выставлен").isNotNull();
        assertThat(flip.side()).isEqualTo(OrderSide.SELL);
        assertThat(flip.quantity())
                .as("продаём вчетверо больше, чем держим: часть закрывает, остаток открывает плечо")
                .isEqualByComparingTo("40");
        assertThat(saved.get().hedgeEpisode()).isNotNull();
        assertThat(saved.get().hedgeEpisodesUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("цель безубытка выставляется заявкой, а не ждёт касания на тике")
    void exitOrderIsPlacedInTheBook() {
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);

        strategy.onPrice(price("21.0"));

        PlaceIntent exit = lastIntentWithPurpose(OrderPurpose.RECOVERY);
        assertThat(exit).as("выход из плеча обязан стоять в стакане").isNotNull();
        assertThat(exit.side())
                .as("плечо после лонга — это шорт, закрывается покупкой")
                .isEqualTo(OrderSide.BUY);
        assertThat(exit.quantity()).isEqualByComparingTo("30");
        assertThat(exit.limitPrice()).isLessThan(new BigDecimal("22"));
    }

    /** Истёк срок — эпизод закрывается по рынку, каким бы ни был результат. */
    @Test
    @DisplayName("по истечении срока плечо закрывается принудительно")
    void expiredEpisodeIsClosed() {
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);
        assertThat(saved.get().hedgeEpisode()).isNotNull();

        // Рынок к этому моменту стоит между целью 19.31 и стопом 21.0: ни то, ни другое
        // не сработало, и закрыть эпизод может ровно одно — истёкший срок. Цену задаём
        // явно, потому что тик спрашивает её у биржи, когда стрим давно молчал.
        when(marketData.getLastPrice(instrumentId)).thenReturn(price("20.5"));
        clockNow.set(now.plusSeconds(60L * 60 * 24 * 10));
        strategy.onTick();

        assertThat(saved.get().hedgeEpisode())
                .as("эпизод обязан завершиться, а не жить вечно")
                .isNull();
        verify(ctx, atLeastOnce()).event(eq(BotEventType.RANGE_EXIT),
                org.mockito.ArgumentMatchers.contains("истёк срок удержания"));
    }

    // ==============================
    // КОГДА ПЕРЕВОРАЧИВАТЬ НЕЛЬЗЯ
    // ==============================

    /**
     * Живая торговля — самый важный из отказов: путь ещё не проверен деньгами,
     * и бот обязан вести себя как раньше.
     */
    @Test
    @DisplayName("в боевом режиме переворот запрещён — работает прежняя ликвидация")
    void liveModeFallsBackToLiquidation() {
        GridStrategy strategy = startedWithPosition(true, true, false);

        breakDownAndConfirm(strategy);

        assertThat(lastIntentWithPurpose(OrderPurpose.HEDGE))
                .as("переворота быть не должно").isNull();
        assertNoEpisode();
    }

    @Test
    @DisplayName("бумага без разрешения на шорт переворачиваться не даёт")
    void instrumentWithoutShortFallsBack() {
        GridStrategy strategy = startedWithPosition(true, false, true);

        breakDownAndConfirm(strategy);

        assertThat(lastIntentWithPurpose(OrderPurpose.HEDGE)).isNull();
        assertNoEpisode();
    }

    @Test
    @DisplayName("без маржи переворот невозможен")
    void withoutMarginFallsBack() {
        GridStrategy strategy = startedWithPosition(false, true, true);

        breakDownAndConfirm(strategy);

        assertThat(lastIntentWithPurpose(OrderPurpose.HEDGE)).isNull();
    }

    /**
     * Второй переворот поверх первого — это и есть рекурсия, которой лимит и
     * поставлен. Один эпизод на поколение по умолчанию.
     */
    @Test
    @DisplayName("второй переворот в том же поколении запрещён")
    void secondEpisodeIsRefused() {
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);
        assertThat(saved.get().hedgeEpisodesUsed()).isEqualTo(1);

        // Эпизод завершился, но счётчик поколения помнит о нём.
        clockNow.set(now.plusSeconds(60L * 60 * 24 * 10));
        strategy.onTick();
        assertThat(saved.get().hedgeEpisode()).isNull();

        int flipsBefore = countIntentsWithPurpose(OrderPurpose.HEDGE);
        clockNow.set(now.plusSeconds(60L * 60 * 24 * 11));
        breakDownAndConfirm(strategy);

        assertThat(countIntentsWithPurpose(OrderPurpose.HEDGE))
                .as("второй переворот в том же поколении не выставляется")
                .isEqualTo(flipsBefore);
    }

    // ==============================
    // ИНФРАСТРУКТУРА
    // ==============================

    /**
     * Эпизода нет ни в каком виде.
     *
     * Состояние могло вовсе не сохраниться — отказ происходит до записи, — и это
     * такой же законный способ сказать «эпизода нет», как сохранённый null.
     */
    private void assertNoEpisode() {
        GridStrategyState state = saved.get();
        assertThat(state == null || state.hedgeEpisode() == null)
                .as("эпизод плеча не должен был начаться")
                .isTrue();
    }

    /** Доводит цену до подтверждённого пробоя нижней границы. */
    private void breakDownAndConfirm(GridStrategy strategy) {
        strategy.onPrice(price("20.0"));
        clockNow.set(clockNow.get().plusSeconds(400));
        strategy.onPrice(price("20.0"));
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), clockNow.get());
    }

    private PlaceIntent lastIntentWithPurpose(OrderPurpose purpose) {
        ArgumentCaptor<PlaceIntent> captor = ArgumentCaptor.forClass(PlaceIntent.class);
        try {
            verify(gateway, atLeastOnce()).placeLimit(any(), captor.capture());
        } catch (AssertionError noOrders) {
            return null;
        }
        return captor.getAllValues().stream()
                .filter(i -> i.purpose() == purpose)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private int countIntentsWithPurpose(OrderPurpose purpose) {
        ArgumentCaptor<PlaceIntent> captor = ArgumentCaptor.forClass(PlaceIntent.class);
        try {
            verify(gateway, atLeastOnce()).placeLimit(any(), captor.capture());
        } catch (AssertionError noOrders) {
            return 0;
        }
        return (int) captor.getAllValues().stream().filter(i -> i.purpose() == purpose).count();
    }

    /** Бот с позицией 10 по себестоимости 220, то есть уже в убытке при цене 21. */
    private GridStrategy startedWithPosition(boolean margin, boolean shortEnabled, boolean dryRun) {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(i -> clockNow.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(
                TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                dryRun, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                margin, shortEnabled, new BigDecimal("1000"), new BigDecimal("1000000")));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.carryDailyRate()).thenReturn(BigDecimal.ZERO);
        when(ctx.marginAttributes()).thenReturn(Optional.empty());
        // Бот с позицией обязан иметь сохранённый диапазон: пересчитывать уровни
        // под открытой позицией он намеренно отказывается. Даём ему состояние,
        // как после рестарта.
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.of(
                new GridStrategyState(
                        new GridRange(new BigDecimal("21.0"), new BigDecimal("23.0"), 10,
                                GridRange.Origin.ATR_INITIAL, now),
                        1)));
        when(ctx.inventory()).thenReturn(new Inventory(
                new BigDecimal("10"), new BigDecimal("220"), new BigDecimal("22")));
        doSaveState();

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId)).thenReturn(price("21.5"));
        when(marketData.getOrderBook(eq(instrumentId), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> new OrderBook(instrumentId, 1,
                        List.of(new OrderBookLevel(new Price(new BigDecimal("20.0"), "rub"), BigDecimal.TEN)),
                        List.of(new OrderBookLevel(new Price(new BigDecimal("20.1"), "rub"), BigDecimal.TEN)),
                        null, null, now));
        when(gateway.openOrders(botId)).thenReturn(List.of());
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.reconcile(any())).thenReturn(reconciled("10"));

        GridStrategy strategy = new GridStrategy(config(margin));
        strategy.onStart(ctx, reconciled("10"));
        strategy.onReconcile(reconciled("10"));
        return strategy;
    }

    private void doSaveState() {
        org.mockito.Mockito.doAnswer(i -> {
            saved.set(i.getArgument(0));
            return null;
        }).when(ctx).saveState(any());
    }

    private ReconcileResult reconciled(String position) {
        BigDecimal p = new BigDecimal(position);
        return new ReconcileResult(List.of(), p, p, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private GridConfig config(boolean margin) {
        return new GridConfig(
                new BigDecimal("21.0"), new BigDecimal("23.0"), 10, new BigDecimal("1"), 10,
                GridConfig.RangeExitAction.REPLACE_LOWER, null, null, true, true,
                null, 6, new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 3, new BigDecimal("10000"),
                null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                GridDirection.LONG, margin, 1,
                margin ? GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER
                        : GridConfig.AdverseBreakoutAction.LIQUIDATE,
                new BigDecimal("4"), 1, 3, new BigDecimal("0.05"), true, margin);
    }
}
