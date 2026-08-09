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
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
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
import static org.mockito.ArgumentMatchers.eq;
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
        when(ctx.constraints()).thenReturn(TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null));
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
        // Диапазон восстановлен, а не пересчитан — за свечами повторно не ходим.
        verify(marketData, times(1)).getCandles(any(), any());
        // А вот цену при рестарте спросить ОБЯЗАНЫ: оценщик ATR теперь не работает,
        // и без этого запроса бот стоял бы без заявок до первой чужой сделки.
        verify(marketData, times(2)).getLastPrice(instrumentId);
    }

    /**
     * Цена, полученная REST-запросом, обязана уйти наружу — иначе бот торгует по ней,
     * а рыночная оценка позиции пустует.
     *
     * Стрим последней цены присылает событие только при СДЕЛКЕ, поэтому на старте его
     * может не быть вовсе; диапазон строится по цене из ответа брокера, и стратегия
     * начинает торговать именно по ней. Пока эта цена никуда не сообщалась, боты
     * T-Invest на боевом сервере 07.08.2026 выставляли сетку, покупали — и показывали
     * прочерки вместо баланса, P/L и рыночной стоимости с подписью
     * «цена из потока не получена».
     */
    @Test
    void priceFetchedOverRestIsReportedForValuation() {
        new GridStrategy(autoConfig()).onStart(ctx, reconciled("0"));

        verify(ctx).observedPrice(eq(new BigDecimal("100")), any());
    }

    @Test
    void missingCheckpointWithOpenPositionRefusesToStart() {
        assertThatThrownBy(() -> new GridStrategy(autoConfig()).onStart(ctx, reconciled("1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("сохранённый диапазон отсутствует");
    }

    /**
     * Свежий бот с ПУСТЫМ журналом обязан стартовать, даже если на счёте что-то лежит.
     *
     * Отказ выше защищает случай «бот покупал по уровням, а диапазон потерялся» —
     * там пересчёт уровней действительно потеряет цены встречных продаж. Но судить
     * об этом надо по журналу бота, а не по остатку счёта: счёт общий. Пока проверялся
     * остаток, только что созданный бот считал своей чужую позицию и не стартовал —
     * ровно так его и заперла неторгуемая пыль в 0.000348 от прошлой жизни.
     *
     * Признать чужое своим — не мелочь: два бота по разным стратегиям на одном
     * инструменте иначе не уживаются в принципе.
     */
    @Test
    void freshBotStartsEvenWhenSomebodyElsesCoinsSitOnTheAccount() {
        // Журнал пуст, а на счёте — пыль от прошлой жизни бота, продать её нельзя.
        ReconcileResult foreignDustOnly = new ReconcileResult(
                List.of(), BigDecimal.ZERO, new BigDecimal("0.000348"), BigDecimal.ZERO,
                0, 0, new BigDecimal("0.000348"));

        assertThatCode(() -> new GridStrategy(autoConfig()).onStart(ctx, foreignDustOnly))
                .doesNotThrowAnyException();
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

    /**
     * Инцидент 08.08.2026, SOL/USDT на Poloniex.
     *
     * Пробой вверх подтверждён, покупки сняты, все продажи исполнились — и бот встал
     * навсегда. В журнале остались 0.0000031120 монеты: сумма хвостов, которые
     * оставляет каждый закрытый цикл там, где комиссия удерживается монетой. Шагу
     * биржи эта сумма уже кратна, но стоит четверть тысячной доллара — заявку на неё
     * не примут никогда. Прежнее условие требовало РОВНО нуля, и бот ждал вечно:
     * ни заявок, ни событий, тупик выглядел как молчание.
     */
    @Test
    void untradableDustDoesNotBlockReplacingRangeUp() {
        withCryptoQuantityLimits();
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0.0000031120"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        GridStrategyState state = saved.get();
        assertThat(state.awaitingUpperReplacement())
                .as("остаток мельче шага количества продать нечем — ждать его вечно")
                .isFalse();
        assertThat(state.generation()).isEqualTo(2);
        assertThat(state.activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_UP);
    }

    /**
     * Обратная сторона допуска: остаток, который биржа принять ГОТОВА, — это деньги,
     * и молча бросать его нельзя. Бот по-прежнему ждёт продажи, но теперь ждёт
     * громко: заявок на бирже нет, значит исполниться нечему и само не рассосётся.
     */
    @Test
    void sellableRemainderKeepsWaitingButSaysSoOnce() {
        withCryptoQuantityLimits();
        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0.5"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(12));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().awaitingUpperReplacement())
                .as("продаваемый остаток бросать нельзя — перестановки не будет")
                .isTrue();
        verify(ctx, times(1)).event(any(),
                org.mockito.ArgumentMatchers.contains("заявок на бирже не осталось"));
    }

    /**
     * Инцидент 08.08.2026, 20:50: биржа закрылась, стрим сообщил об этом двум ботам
     * из трёх. Третий события не увидел и до утра ставил заявку каждую минуту, получая
     * «Instrument is not available for trading» — сотни строк в журнале и ни одной
     * сделки. Отказ в постановке теперь повод переспросить биржу, а не молча повторять.
     */
    @Test
    void rejectedOrderMakesTheBotAskWhetherTheExchangeClosed() {
        when(gateway.placeLimit(any(), any())).thenThrow(new IllegalStateException("30079"));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now),
                new TradingStatusEvent(instrumentId, false, false, "NOT_AVAILABLE_FOR_TRADING", now));

        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onPrice(lastPrice("100"));

        verify(ctx).event(any(), org.mockito.ArgumentMatchers.contains("Торги закрыты"));

        // И, главное, повторять отвергнутую заявку бот больше не пытается.
        org.mockito.Mockito.clearInvocations(gateway);
        strategy.onPrice(lastPrice("100"));
        verify(gateway, never()).placeLimit(any(), any());
    }

    /**
     * Заявка на продажу пыли не должна задерживать перестановку.
     *
     * Пыль копится ЧЕРЕЗ поколения диапазона, поэтому её продажа переживает
     * перестановку и в проверке «наших заявок на бирже не осталось» не участвует.
     * Иначе мы вернули бы тупик 08.08.2026 — теперь уже с заявкой на бирже.
     */
    @Test
    void dustSaleDoesNotHoldUpTheReplacement() {
        BotOrderView dustSale = mock(BotOrderView.class);
        when(dustSale.side()).thenReturn(OrderSide.SELL);
        when(dustSale.purpose()).thenReturn(ru.larionov.backend.enums.OrderPurpose.DUST);
        when(gateway.openOrders(botId)).thenReturn(List.of(dustSale));

        GridStrategy strategy = new GridStrategy(replaceUpperConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onReconcile(reconciled("0"));

        strategy.onPrice(lastPrice("111"));
        currentTime.set(now.plusSeconds(11));
        strategy.onPrice(lastPrice("111"));

        assertThat(saved.get().generation()).isEqualTo(2);
        assertThat(saved.get().awaitingUpperReplacement()).isFalse();
        // И сама заявка на пыль при этом не снята: её товар к сетке не относится.
        verify(gateway, never()).cancel(any(), any());
    }

    /**
     * Инцидент 09.08.2026: бот на MVID полдня бился об «30099 The price is outside
     * the limits for this instrument» — цена уровня вышла за дневной коридор бумаги.
     * Биржа при этом открыта, так что переспрашивать её статус бесполезно, а повтор
     * через минуту не лечит ничего: коридор от повторов не раздвигается. Каждая
     * попытка оставляла запись PENDING и строку ERROR в журнале.
     */
    @Test
    void aRejectedLevelIsNotRetriedEveryTick() {
        // Биржа открыта: у неё и спрашивать нечего, коридор цен от этого не раздвинется.
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        // Как на MVID: коридор бумаги отсекает ровно один уровень, остальные проходят.
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            ru.larionov.backend.execution.PlaceIntent intent = invocation.getArgument(1);
            if (Integer.valueOf(0).equals(intent.gridLevel())) {
                throw new IllegalStateException("30099 The price is outside the limits");
            }
            return null;
        });

        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onPrice(lastPrice("100"));

        currentTime.set(now.plusSeconds(60));
        strategy.onPrice(lastPrice("100"));
        currentTime.set(now.plusSeconds(120));
        strategy.onPrice(lastPrice("100"));

        assertThat(attemptsAtLevel(0))
                .as("отвергнутый уровень не должен долбиться в биржу каждый тик")
                .isEqualTo(1);
        assertThat(failureReports())
                .as("и повторять одну и ту же строку в журнале тоже не должен")
                .isEqualTo(1);
        assertThat(attemptsAtLevel(1))
                .as("соседние уровни коридор не отсекал — они обязаны выставиться")
                .isPositive();
    }

    /** Сколько раз стратегия дошла до биржи с постановкой на этом уровне. */
    private long attemptsAtLevel(int level) {
        return org.mockito.Mockito.mockingDetails(gateway).getInvocations().stream()
                .filter(i -> "placeLimit".equals(i.getMethod().getName()))
                .filter(i -> i.getArguments().length > 1
                        && i.getArguments()[1] instanceof ru.larionov.backend.execution.PlaceIntent intent
                        && Integer.valueOf(level).equals(intent.gridLevel()))
                .count();
    }

    /** Сколько раз про отказ написано в журнал событий. */
    private long failureReports() {
        return org.mockito.Mockito.mockingDetails(ctx).getInvocations().stream()
                .filter(i -> "error".equals(i.getMethod().getName()))
                .filter(i -> i.getArguments().length > 0
                        && String.valueOf(i.getArguments()[0]).contains("Повторю не раньше"))
                .count();
    }

    /** Новая сессия — новый коридор цен: пауза после отказа к ней отношения не имеет. */
    @Test
    void anOpeningSessionClearsThePlacementCooldown() {
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(gateway.placeLimit(any(), any())).thenThrow(new IllegalStateException("30099"));

        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled("0"));
        strategy.onPrice(lastPrice("100"));
        org.mockito.Mockito.clearInvocations(gateway);

        strategy.onTradingStatus(new TradingStatusEvent(
                instrumentId, false, false, "CLOSED", now));
        strategy.onTradingStatus(new TradingStatusEvent(
                instrumentId, true, true, "NORMAL_TRADING", now));

        verify(gateway, org.mockito.Mockito.atLeastOnce()).placeLimit(any(), any());
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
        strategy.onReconcile(shortfall());
        strategy.onReconcile(shortfall());

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
        strategy.onReconcile(shortfall());
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
                false, BigDecimal.ONE, BigDecimal.ONE, null,
                new BigDecimal("400"), null, null, null));
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

    /**
     * Криптовые ограничения: монету можно дробить до шести знаков, но заявку дешевле
     * минимальной суммы биржа не примет. Пыль живёт ровно между этими двумя числами.
     */
    private void withCryptoQuantityLimits() {
        BigDecimal step = new BigDecimal("0.000001");
        when(ctx.constraints()).thenReturn(new TradingConstraints(
                BigDecimal.ONE, step, step, null, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, step, new BigDecimal("0.1"),
                null, null, null, null));
    }

    private GridConfig autoConfig() {
        return new GridConfig(
                null, null, 4, new BigDecimal("1"), 4,
                GridConfig.RangeExitAction.STOP_BUYING, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 300, new BigDecimal("0.002"),
                1200, 0, null,
                null, null, null);
    }

    private GridConfig replaceUpperConfig() {
        return new GridConfig(
                null, null, 4, new BigDecimal("1"), 4,
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

    /** Недостача: журнал числит за ботом единицу, а на счёте пусто. */
    private ReconcileResult shortfall() {
        return new ReconcileResult(List.of(), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ONE.negate());
    }

    private ReconcileResult reconciled(String position) {
        return new ReconcileResult(List.of(), new BigDecimal(position), new BigDecimal(position), BigDecimal.ZERO,
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
