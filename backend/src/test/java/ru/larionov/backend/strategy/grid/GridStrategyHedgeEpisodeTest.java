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
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
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
import static org.mockito.ArgumentMatchers.argThat;
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
    private List<BotOrderView> hedgeOrderHistory;
    private boolean fillHedgeImmediately;
    private boolean fillAggressiveRecoveryImmediately;

    @BeforeEach
    void setUp() {
        botId = UUID.randomUUID();
        clockNow = new AtomicReference<>(now);
        saved = new AtomicReference<>();
        hedgeOrderHistory = new ArrayList<>();
        fillHedgeImmediately = true;
        fillAggressiveRecoveryImmediately = true;
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
        assertThat(saved.get().direction())
                .as("вместе с позицией разворачивается сама сетка")
                .isEqualTo(GridDirection.SHORT);
        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().activeRange().origin())
                .as("лонг пробит вниз — новое поколение физически ниже")
                .isEqualTo(GridRange.Origin.ATR_REPLACED_DOWN);
    }

    @Test
    @DisplayName("пробой шорта вверх переворачивает сетку в лонг без разрешения на шорт")
    void shortBreakoutFlipsToLongWithoutShortPermission() {
        GridStrategy strategy = startedWithPosition(
                GridDirection.SHORT, true, true, true);
        // Шорт уже открыт законно, но перед пробоем брокер снял флаг доступности.
        // Это не должно запрещать BUY, который закрывает шорт и открывает лонг.
        when(ctx.execution()).thenReturn(executionContext(true, false, true));

        breakUpAndConfirm(strategy);

        PlaceIntent flip = lastIntentWithPurpose(OrderPurpose.HEDGE);
        assertThat(flip).as("шорт закрывается и переворачивается одной покупкой").isNotNull();
        assertThat(flip.side()).isEqualTo(OrderSide.BUY);
        assertThat(flip.quantity()).isEqualByComparingTo("40");
        assertThat(saved.get().direction()).isEqualTo(GridDirection.LONG);
        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().activeRange().origin())
                .as("шорт пробит вверх — новое поколение физически выше")
                .isEqualTo(GridRange.Origin.ATR_REPLACED_UP);
        verify(ctx, never()).event(eq(BotEventType.RISK_BLOCKED),
                org.mockito.ArgumentMatchers.contains("Брокер не шортит"));
    }

    @Test
    @DisplayName("если переворот шорта запрещён, аварийное закрытие выставляет BUY")
    void shortFallbackLiquidationBuysToClose() {
        GridStrategy strategy = startedWithPosition(
                GridDirection.SHORT, true, true, true, 0);

        breakUpAndConfirm(strategy);

        assertThat(lastIntentWithPurpose(OrderPurpose.HEDGE)).isNull();
        PlaceIntent liquidation = lastIntentWithPurpose(OrderPurpose.LIQUIDATION);
        assertThat(liquidation).as("короткую позицию закрывает зеркальная заявка").isNotNull();
        assertThat(liquidation.side()).isEqualTo(OrderSide.BUY);
        assertThat(liquidation.quantity()).isEqualByComparingTo("10");
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

    @Test
    @DisplayName("принятая HEDGE не открывает эпизод до фактического исполнения")
    void acceptedEntryWaitsForFill() {
        fillHedgeImmediately = false;
        GridStrategy strategy = startedWithPosition(true, true, true);

        breakDownAndConfirm(strategy);

        assertThat(saved.get().hedgeEpisode().opening()).isTrue();
        assertThat(lastIntentWithPurpose(OrderPurpose.RECOVERY)).isNull();

        BotOrderView entry = lastOrder(OrderPurpose.HEDGE);
        BotOrderView filled = withExecution(entry, OrderStatus.FILLED,
                entry.requestedQuantity(), new BigDecimal("19.95"));
        replaceOrder(filled);
        strategy.onOrderUpdate(filled);

        assertThat(saved.get().hedgeEpisode().active()).isTrue();
        assertThat(saved.get().hedgeEpisode().entryPrice()).isEqualByComparingTo("19.95");
    }

    @Test
    @DisplayName("частичный HEDGE дозаявляет остаток и считает вход по взвешенной цене")
    void partialEntryIsCompletedAtActualWeightedPrice() {
        fillHedgeImmediately = false;
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);

        BotOrderView first = lastOrder(OrderPurpose.HEDGE);
        BotOrderView partial = withExecution(first, OrderStatus.EXPIRED,
                new BigDecimal("15"), new BigDecimal("19.90"));
        replaceOrder(partial);
        strategy.onOrderUpdate(partial);

        BotOrderView retry = lastOrder(OrderPurpose.HEDGE);
        assertThat(retry.id()).isNotEqualTo(first.id());
        assertThat(retry.requestedQuantity()).isEqualByComparingTo("25");
        assertThat(saved.get().hedgeEpisode().opening()).isTrue();

        BotOrderView filled = withExecution(retry, OrderStatus.FILLED,
                new BigDecimal("25"), new BigDecimal("20.00"));
        replaceOrder(filled);
        strategy.onOrderUpdate(filled);

        assertThat(saved.get().hedgeEpisode().active()).isTrue();
        assertThat(saved.get().hedgeEpisode().entryPrice()).isEqualByComparingTo("19.962500000");
        assertThat(saved.get().hedgeEpisode().hedgeQuantity()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("частичный и истёкший RECOVERY дозакрывается, эпизод живёт до FILLED")
    void partialExpiredExitIsRetriedUntilFullyFilled() {
        fillAggressiveRecoveryImmediately = false;
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);

        strategy.onPrice(price("21.10"));
        assertThat(saved.get().hedgeEpisode().closing()).isTrue();

        BotOrderView firstExit = lastOrder(OrderPurpose.RECOVERY);
        BotOrderView partial = withExecution(firstExit, OrderStatus.PARTIALLY_FILLED,
                new BigDecimal("10"), firstExit.limitPrice());
        replaceOrder(partial);
        strategy.onOrderUpdate(partial);
        assertThat(saved.get().hedgeEpisode()).isNotNull();

        BotOrderView expired = withExecution(partial, OrderStatus.EXPIRED,
                new BigDecimal("10"), firstExit.limitPrice());
        replaceOrder(expired);
        strategy.onOrderUpdate(expired);

        BotOrderView retry = lastOrder(OrderPurpose.RECOVERY);
        assertThat(retry.id()).isNotEqualTo(firstExit.id());
        assertThat(retry.requestedQuantity()).isEqualByComparingTo("20");
        assertThat(saved.get().hedgeEpisode().closing()).isTrue();

        BotOrderView filled = withExecution(retry, OrderStatus.FILLED,
                retry.requestedQuantity(), retry.limitPrice());
        replaceOrder(filled);
        strategy.onOrderUpdate(filled);

        assertThat(saved.get().hedgeEpisode()).isNull();
    }

    @Test
    @DisplayName("рестарт в CLOSING восстанавливает выход по оставшемуся объёму")
    void restartWhileClosingRestoresExit() {
        fillAggressiveRecoveryImmediately = false;
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);
        strategy.onPrice(price("21.10"));

        BotOrderView firstExit = lastOrder(OrderPurpose.RECOVERY);
        replaceOrder(withExecution(firstExit, OrderStatus.EXPIRED,
                BigDecimal.ZERO, null));
        GridStrategyState closingState = saved.get();
        int exitsBeforeRestart = countIntentsWithPurpose(OrderPurpose.RECOVERY);

        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.of(closingState));
        when(gateway.reconcile(any())).thenReturn(reconciled("-30"));
        GridStrategy restarted = new GridStrategy(config(true));
        restarted.onStart(ctx, reconciled("-30"));
        restarted.onReconcile(reconciled("-30"));
        restarted.onTick();

        assertThat(saved.get().hedgeEpisode().closing()).isTrue();
        assertThat(countIntentsWithPurpose(OrderPurpose.RECOVERY))
                .isGreaterThan(exitsBeforeRestart);
        assertThat(lastOrder(OrderPurpose.RECOVERY).requestedQuantity())
                .isEqualByComparingTo("30");
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

    /**
     * Инцидент 14.08.2026: бот выключился навсегда с непокрытым шортом на счёте.
     *
     * После переворота ×4 позиция счёта стала −420 — это ЦЕЛИКОМ плечо, позицию сетки
     * переворот уже закрыл. Но сетка работает одновременно с плечом, подтвердила
     * следующий пробой и пошла закрывать «свою» позицию. Увидев на счёте минус,
     * ликвидация сочла это непоправимым разъездом с биржей и остановила бота: шорт
     * на 420 штук остался без стопа, без цели и без присмотра.
     *
     * Сетка обязана считать своей только свою позицию.
     */
    @Test
    @DisplayName("перестановка вниз при живом плече не принимает его за разъезд с биржей")
    void downwardReplacementIgnoresTheOpenHedgeLeg() {
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);
        assertThat(saved.get().hedgeEpisode()).as("плечо открыто").isNotNull();

        // Переворот исполнился: на счёте осталась только нога плеча, 30 в шорт.
        BigDecimal hedgeLeg = saved.get().hedgeEpisode().hedgeQuantity().negate();
        when(gateway.reconcile(any())).thenReturn(reconciled(hedgeLeg.toPlainString()));
        strategy.onReconcile(reconciled(hedgeLeg.toPlainString()));

        // Цена продолжает падать — сетка подтверждает ещё один пробой.
        clockNow.set(clockNow.get().plusSeconds(400));
        strategy.onPrice(price("19.5"));
        clockNow.set(clockNow.get().plusSeconds(400));
        strategy.onPrice(price("19.5"));

        verify(ctx, never()).requestStop(
                org.mockito.ArgumentMatchers.contains("короткую позицию во время ликвидации"));
        assertThat(saved.get().hedgeEpisode())
                .as("плечо продолжает жить: его ведёт эпизод, а не перестановка сетки")
                .isNotNull();
    }

    /**
     * Заявка выхода из плеча обязана пережить перестановку диапазона.
     *
     * Она стоит в стакане по цене безубытка НЕПОКРЫТОЙ позиции и является
     * единственной её защитой. Перестановка снимала заявки бота скопом, вместе
     * с ней, — и шорт оставался без выхода ровно в тот момент, когда рынок идёт
     * против позиции. Заодно проверяем обратное: ждать её исчезновения нельзя,
     * иначе сетка не переставится никогда — пока эпизод жив, заявка стоит.
     */
    @Test
    @DisplayName("перестановка не снимает заявку выхода из плеча и не ждёт её")
    void replacementSparesTheHedgeExitOrder() {
        GridStrategy strategy = startedWithPosition(true, true, true);
        breakDownAndConfirm(strategy);
        assertThat(saved.get().hedgeEpisode()).as("плечо открыто").isNotNull();

        // Переворот исполнился: на счёте только нога плеча, а в стакане — её выход.
        BotOrderView hedgeExit = hedgeExitOrder();
        hedgeOrderHistory.add(hedgeExit);
        when(gateway.openOrders(botId)).thenReturn(List.of(hedgeExit));
        BigDecimal leg = saved.get().hedgeEpisode().hedgeQuantity().negate();
        when(gateway.reconcile(any())).thenReturn(reconciled(leg.toPlainString()));
        strategy.onReconcile(reconciled(leg.toPlainString()));

        long generationBefore = saved.get().generation();

        // Падение продолжается. После переворота сетка уже шортовая, поэтому это
        // благоприятное движение, а не ещё одна аварийная перестановка.
        clockNow.set(clockNow.get().plusSeconds(400));
        strategy.onPrice(price("19.5"));
        clockNow.set(clockNow.get().plusSeconds(400));
        strategy.onPrice(price("19.5"));

        verify(gateway, never()).cancelAll(any());
        verify(gateway).cancelAllExcept(any(), argThat(keep ->
                keep.contains(OrderPurpose.RECOVERY) && keep.contains(OrderPurpose.HEDGE)));
        verify(gateway, never()).cancel(any(), eq(hedgeExit.id()));
        assertThat(saved.get().hedgeEpisode())
                .as("эпизод продолжает жить со своей защитой в стакане")
                .isNotNull();
        assertThat(saved.get().generation())
                .as("сетку перевернули вместе с хеджем; лишней перестановки больше нет")
                .isEqualTo(generationBefore);
    }

    /**
     * Выход из плеча: без уровня сетки и НАМЕРЕННО без явной роли.
     *
     * Так выглядит заявка, поднятая сверкой с биржи: роль там взять неоткуда, и
     * выводится она из стороны — покупка, то есть «открывающая». Защита обязана
     * держаться на назначении, а не на роли, иначе такая заявка будет снята.
     */
    private BotOrderView hedgeExitOrder() {
        UUID id = UUID.randomUUID();
        return new BotOrderView(
                id, id.toString(), "exch-" + id, OrderSide.BUY, OrderStatus.NEW, null,
                OrderPurpose.RECOVERY, new BigDecimal("30"), BigDecimal.ZERO,
                new BigDecimal("19.31"), new BigDecimal("19.31"), null, false, null, null,
                "rub", BigDecimal.ONE, false, null, clockNow.get(), clockNow.get());
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

    /** Доводит цену до подтверждённого пробоя верхней границы шортовой сетки. */
    private void breakUpAndConfirm(GridStrategy strategy) {
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
        return startedWithPosition(GridDirection.LONG, margin, shortEnabled, dryRun);
    }

    /**
     * Лонг хранится как +10/+220 в диапазоне 21..23, шорт — как -10/-180 в 17..19.
     * В обоих случаях цена 20 находится за неблагоприятной границей и даёт реальный
     * убыток, который может отбить противоположная нога ×4.
     */
    private GridStrategy startedWithPosition(GridDirection initialDirection,
                                             boolean margin,
                                             boolean shortEnabled,
                                             boolean dryRun) {
        return startedWithPosition(initialDirection, margin, shortEnabled, dryRun, 1);
    }

    private GridStrategy startedWithPosition(GridDirection initialDirection,
                                             boolean margin,
                                             boolean shortEnabled,
                                             boolean dryRun,
                                             int maxHedgeEpisodes) {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);

        boolean longPosition = initialDirection == GridDirection.LONG;
        BigDecimal lower = new BigDecimal(longPosition ? "21.0" : "17.0");
        BigDecimal upper = new BigDecimal(longPosition ? "23.0" : "19.0");
        BigDecimal position = new BigDecimal(longPosition ? "10" : "-10");
        BigDecimal costBasis = new BigDecimal(longPosition ? "220" : "-180");
        BigDecimal averagePrice = new BigDecimal(longPosition ? "22" : "18");

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(i -> clockNow.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(
                TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(executionContext(margin, shortEnabled, dryRun));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.carryDailyRate()).thenReturn(BigDecimal.ZERO);
        when(ctx.marginAttributes()).thenReturn(Optional.empty());
        // Бот с позицией обязан иметь сохранённый диапазон: пересчитывать уровни
        // под открытой позицией он намеренно отказывается. Даём ему состояние,
        // как после рестарта.
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.of(
                new GridStrategyState(
                        new GridRange(lower, upper, 10,
                                GridRange.Origin.ATR_INITIAL, now),
                        1, false, null, false, null, 0, BigDecimal.ZERO, null,
                        false, false, null, 0, initialDirection)));
        when(ctx.inventory()).thenReturn(new Inventory(
                position, costBasis, averagePrice));
        doSaveState();

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId)).thenReturn(price("21.5"));
        // Свечи нужны оценке нового диапазона при перестановке вниз.
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(marketData.getOrderBook(eq(instrumentId), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> new OrderBook(instrumentId, 1,
                        List.of(new OrderBookLevel(new Price(new BigDecimal("20.0"), "rub"), BigDecimal.TEN)),
                        List.of(new OrderBookLevel(new Price(new BigDecimal("20.1"), "rub"), BigDecimal.TEN)),
                        null, null, now));
        when(gateway.openOrders(botId)).thenReturn(List.of());
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.purposeOrders(eq(botId), any(), any())).thenAnswer(invocation -> {
            OrderPurpose purpose = invocation.getArgument(1);
            return hedgeOrderHistory.stream().filter(o -> o.purpose() == purpose).toList();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            OrderStatus status = (intent.purpose() == OrderPurpose.HEDGE
                    && fillHedgeImmediately)
                    || (intent.purpose() == OrderPurpose.RECOVERY
                    && fillAggressiveRecoveryImmediately
                    && intent.limitPrice().compareTo(new BigDecimal("20")) >= 0)
                    ? OrderStatus.FILLED : OrderStatus.NEW;
            BigDecimal executed = status == OrderStatus.FILLED
                    ? intent.quantity() : BigDecimal.ZERO;
            UUID id = UUID.randomUUID();
            BotOrderView order = new BotOrderView(
                    id, id.toString(), "exch-" + id, intent.side(), status,
                    intent.gridLevel(), intent.purpose(), intent.role(), intent.quantity(),
                    executed, intent.limitPrice(), status == OrderStatus.FILLED
                            ? intent.limitPrice() : null,
                    null, false, null, null, "rub", BigDecimal.ONE,
                    true, null, clockNow.get(), clockNow.get());
            if (intent.purpose() == OrderPurpose.HEDGE
                    || intent.purpose() == OrderPurpose.RECOVERY) {
                hedgeOrderHistory.add(order);
            }
            return order;
        }).when(gateway).placeLimit(any(), any());
        when(gateway.reconcile(any())).thenReturn(reconciled(position.toPlainString()));

        GridStrategy strategy = new GridStrategy(config(
                margin, initialDirection, lower, upper, maxHedgeEpisodes));
        strategy.onStart(ctx, reconciled(position.toPlainString()));
        strategy.onReconcile(reconciled(position.toPlainString()));
        return strategy;
    }

    private BotExecutionContext executionContext(boolean margin, boolean shortEnabled,
                                                 boolean dryRun) {
        return new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                dryRun, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                margin, shortEnabled, new BigDecimal("1000"), new BigDecimal("1000000"));
    }

    private void doSaveState() {
        org.mockito.Mockito.doAnswer(i -> {
            saved.set(i.getArgument(0));
            return null;
        }).when(ctx).saveState(any());
    }

    private BotOrderView lastOrder(OrderPurpose purpose) {
        return hedgeOrderHistory.stream()
                .filter(o -> o.purpose() == purpose)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private void replaceOrder(BotOrderView replacement) {
        hedgeOrderHistory.replaceAll(existing -> existing.id().equals(replacement.id())
                ? replacement : existing);
    }

    private BotOrderView withExecution(BotOrderView order, OrderStatus status,
                                       BigDecimal executed, BigDecimal averagePrice) {
        return new BotOrderView(order.id(), order.clientOrderId(), order.exchangeOrderId(),
                order.side(), status, order.gridLevel(), order.purpose(), order.gridRole(),
                order.requestedQuantity(), executed, order.limitPrice(), averagePrice,
                order.fee(), order.feeActual(), order.feeRate(), order.feeSource(),
                order.feeCurrency(), order.exchangeLotSize(), order.dryRun(), order.lastError(),
                order.createdAt(), clockNow.get());
    }

    private ReconcileResult reconciled(String position) {
        BigDecimal p = new BigDecimal(position);
        return new ReconcileResult(List.of(), p, p, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private GridConfig config(boolean margin) {
        return config(margin, GridDirection.LONG,
                new BigDecimal("21.0"), new BigDecimal("23.0"));
    }

    private GridConfig config(boolean margin, GridDirection direction,
                              BigDecimal lower, BigDecimal upper) {
        return config(margin, direction, lower, upper, 1);
    }

    private GridConfig config(boolean margin, GridDirection direction,
                              BigDecimal lower, BigDecimal upper,
                              int maxHedgeEpisodes) {
        return new GridConfig(
                lower, upper, 10, new BigDecimal("1"), 10,
                GridConfig.RangeExitAction.REPLACE_LOWER, null, null, true, true,
                null, 6, new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 3, new BigDecimal("10000"),
                null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                direction, margin, 1,
                margin ? GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER
                        : GridConfig.AdverseBreakoutAction.LIQUIDATE,
                new BigDecimal("4"), maxHedgeEpisodes, 3,
                new BigDecimal("0.05"), true, margin);
    }

    private List<Candle> candles() {
        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new Candle(instrumentId,
                        new Price(new BigDecimal("20"), "rub"),
                        new Price(new BigDecimal("20.4"), "rub"),
                        new Price(new BigDecimal("19.6"), "rub"),
                        new Price(new BigDecimal("20"), "rub"),
                        BigDecimal.ONE, now.minusSeconds((6L - i) * 3600), null))
                .toList();
    }
}
