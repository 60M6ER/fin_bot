package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Храповик цели восстановительного плеча.
 *
 * Проверяется ровно то, ради чего он заведён: цель едет за ценой в выгодную сторону
 * и НИКОГДА обратно, при развороте эпизод закрывается по последней достигнутой цели,
 * а в прежнем режиме BREAKEVEN_TARGET не меняется ничего.
 *
 * Числа здесь простые и подобранные вручную — это не случайность. Арифметика плеча
 * проверена в {@link HedgeMathTest}; здесь важно, чтобы сравнения цен читались глазами,
 * потому что ошибка в направлении сравнения не падает, а тихо закрывает эпизод не там.
 */
class HedgeTrailingTest {

    private static final BigDecimal OFFSET = new BigDecimal("0.01");   // 1% отступа
    private static final BigDecimal STEP = new BigDecimal("0.01");     // шаг цены
    private static final Instant OPENED = Instant.parse("2026-01-08T12:00:00Z");

    /** Шортовое плечо: продали по 100, безубыток внизу на 90, ждём падения. */
    private HedgeEpisode shortLeg() {
        return episode(GridDirection.SHORT, new BigDecimal("100"), new BigDecimal("90"));
    }

    /** Длинное плечо: зеркало шортового — купили по 100, безубыток вверху на 110. */
    private HedgeEpisode longLeg() {
        return episode(GridDirection.LONG, new BigDecimal("100"), new BigDecimal("110"));
    }

    private HedgeEpisode episode(GridDirection direction, BigDecimal entry, BigDecimal target) {
        return new HedgeEpisode(UUID.randomUUID(), direction, OPENED, entry,
                new BigDecimal("30"), target, new BigDecimal("4"), new BigDecimal("-20.1"),
                OPENED.plusSeconds(3 * 86400), null);
    }

    private HedgeEpisode trail(HedgeEpisode episode, String price) {
        return episode.trailedTo(new BigDecimal(price), OFFSET, STEP);
    }

    // ==============================
    // ВЗВЕДЕНИЕ
    // ==============================

    @Test
    @DisplayName("до безубытка храповик не взводится: защищать в убытке нечего")
    void doesNotArmBeforeTarget() {
        HedgeEpisode episode = shortLeg();

        assertThat(trail(episode, "95").trailing())
                .as("цена ещё выше цели — эпизод в убытке, трейлингу там делать нечего")
                .isFalse();
        assertThat(trail(episode, "90.01").trailing()).isFalse();
    }

    @Test
    @DisplayName("цель, пройденная с запасом, взводит храповик вместо закрытия")
    void armsWhenPriceRunsPastTarget() {
        HedgeEpisode armed = trail(shortLeg(), "80");

        assertThat(armed.trailing()).isTrue();
        assertThat(armed.trailingTarget())
                .as("цель встаёт на отступ ВЫШЕ цены: до неё надо откатить, а не дойти")
                .isEqualByComparingTo("80.80");
        assertThat(armed.targetReached(new BigDecimal("80")))
                .as("на самой цене эпизод не закрывается — иначе храповик закрывал бы сам себя")
                .isFalse();
    }

    @Test
    @DisplayName("хуже безубытка подтянутая цель не встаёт")
    void neverArmsWorseThanBreakeven() {
        // Цена ровно на цели: отступ увёл бы цель ВЫШЕ безубытка, то есть в убыток.
        HedgeEpisode armed = trail(shortLeg(), "90");

        assertThat(armed.trailingTarget())
                .as("ограничение безубытком: худший исход эпизода — та же цена, что и без храповика")
                .isEqualByComparingTo("90");
        assertThat(armed.targetReached(new BigDecimal("90")))
                .as("а раз цель равна безубытку и цена на ней — закрываемся, как закрывались раньше")
                .isTrue();
    }

    // ==============================
    // ХРАПОВИК
    // ==============================

    @Test
    @DisplayName("цель едет за ценой только в выгодную сторону")
    void targetMovesOnlyTowardsProfit() {
        HedgeEpisode episode = trail(shortLeg(), "80");
        assertThat(episode.trailingTarget()).isEqualByComparingTo("80.80");

        episode = trail(episode, "70");
        assertThat(episode.trailingTarget())
                .as("падение продолжилось — цель поехала вниз следом")
                .isEqualByComparingTo("70.70");

        HedgeEpisode afterPullback = trail(episode, "75");
        assertThat(afterPullback)
                .as("откат цель не двигает — иначе это не защита прибыли, а надежда")
                .isSameAs(episode);
        assertThat(afterPullback.trailingTarget()).isEqualByComparingTo("70.70");

        HedgeEpisode afterNewLow = trail(afterPullback, "69");
        assertThat(afterNewLow.trailingTarget())
                .as("новый минимум — и только он — двигает цель дальше")
                .isEqualByComparingTo("69.69");
    }

    @Test
    @DisplayName("сдвиг мельче шага цены цель не трогает")
    void ignoresMovesSmallerThanPriceStep() {
        HedgeEpisode episode = trail(shortLeg(), "70");

        // 69.995 дало бы цель 70.69495 — выигрыш полкопейки при шаге в копейку.
        assertThat(trail(episode, "69.995"))
                .as("иначе цель переписывалась бы и сохранялась в базу на каждом тике движения")
                .isSameAs(episode);
    }

    @Test
    @DisplayName("при развороте закрываемся по последней достигнутой цели, а не по исходной")
    void closesAtTheLastRatchetedTarget() {
        HedgeEpisode episode = trail(trail(shortLeg(), "80"), "70");
        assertThat(episode.trailingTarget()).isEqualByComparingTo("70.70");

        assertThat(episode.targetReached(new BigDecimal("70.69")))
                .as("до цели ещё не откатили — держим")
                .isFalse();
        assertThat(episode.targetReached(new BigDecimal("70.70")))
                .as("откат до цели — выходим здесь, а не по исходным 90")
                .isTrue();
        assertThat(episode.targetReached(new BigDecimal("89")))
                .as("исходная цель осталась позади и закрывать по ней уже нечего")
                .isTrue();
    }

    @Test
    @DisplayName("длинное плечо ведёт себя зеркально")
    void longLegMirrorsShort() {
        HedgeEpisode episode = trail(longLeg(), "120");
        assertThat(episode.trailingTarget())
                .as("цель на отступ НИЖЕ цены")
                .isEqualByComparingTo("118.80");

        episode = trail(episode, "130");
        assertThat(episode.trailingTarget()).isEqualByComparingTo("128.70");

        assertThat(trail(episode, "125"))
                .as("откат вниз цель не опускает")
                .isSameAs(episode);
        assertThat(episode.targetReached(new BigDecimal("128.71"))).isFalse();
        assertThat(episode.targetReached(new BigDecimal("128.70"))).isTrue();
    }

    // ==============================
    // ПРЕЖНИЙ РЕЖИМ
    // ==============================

    /**
     * Эпизод без храповика обязан вести себя так же, как вёл до его появления, —
     * и это не вопрос вкуса: по этой цене в стакане стоит заявка выхода.
     */
    @Nested
    @DisplayName("BREAKEVEN_TARGET")
    class BreakevenUnchanged {

        @Test
        @DisplayName("цель эпизода без храповика — расчётная, и достигается движением к ней")
        void staticTargetKeepsItsMeaning() {
            HedgeEpisode episode = shortLeg();

            assertThat(episode.trailing()).isFalse();
            assertThat(episode.effectiveTarget()).isEqualByComparingTo("90");
            assertThat(episode.targetReached(new BigDecimal("90.01"))).isFalse();
            assertThat(episode.targetReached(new BigDecimal("90"))).isTrue();
            assertThat(episode.targetReached(new BigDecimal("80")))
                    .as("ушли дальше цели — тем более достигнута")
                    .isTrue();
        }

        @Test
        @DisplayName("нулевой отступ храповик не взводит")
        void zeroOffsetNeverArms() {
            HedgeEpisode episode = shortLeg();

            assertThat(episode.trailedTo(new BigDecimal("80"), BigDecimal.ZERO, STEP))
                    .isSameAs(episode);
            assertThat(episode.trailedTo(new BigDecimal("80"), null, STEP))
                    .isSameAs(episode);
        }

        /**
         * Подтянутая цель обязана пережить рестарт вместе с эпизодом.
         *
         * Пока эпизод открыт, на бирже висит непокрытая позиция, а цель — единственное
         * знание о том, по какой цене её закрывать. Потерять её при перезапуске значит
         * отдать всё, что храповик набрал.
         */
        @Test
        @DisplayName("подтянутая цель переживает сохранение состояния")
        void trailingTargetSurvivesRoundTrip() {
            HedgeEpisode armed = trail(trail(shortLeg(), "80"), "70");
            ObjectMapper mapper = new ObjectMapper();

            HedgeEpisode restored = mapper.readValue(
                    mapper.writeValueAsString(armed), HedgeEpisode.class);

            assertThat(restored.trailingTarget()).isEqualByComparingTo("70.70");
            assertThat(restored.targetPrice())
                    .as("исходный безубыток тоже остаётся: по нему считается результат эпизода")
                    .isEqualByComparingTo("90");
            assertThat(restored.targetReached(new BigDecimal("70.70")))
                    .as("и смысл сравнения после восстановления тот же")
                    .isTrue();
        }

        @Test
        @DisplayName("состояние, записанное до трейлинга, читается как эпизод без храповика")
        void stateWrittenBeforeTrailingStillReads() {
            ObjectMapper mapper = new ObjectMapper();
            String json = """
                    {"episodeId":"%s","direction":"SHORT","openedAt":"2026-01-08T12:00:00Z",
                     "entryPrice":100,"hedgeQuantity":30,"targetPrice":90,"multiplier":4,
                     "lossAtEntry":-20.1,"deadline":"2026-01-11T12:00:00Z","stopPrice":null}
                    """.formatted(UUID.randomUUID());

            HedgeEpisode restored = mapper.readValue(json, HedgeEpisode.class);

            assertThat(restored.trailing()).isFalse();
            assertThat(restored.effectiveTarget()).isEqualByComparingTo("90");
        }

        @Test
        @DisplayName("режим по умолчанию — прежний выход по безубытку")
        void defaultConfigKeepsBreakevenMode() {
            GridConfig cfg = new GridConfig(
                    new BigDecimal("21.0"), new BigDecimal("23.0"), 10, new BigDecimal("1"), 10,
                    GridConfig.RangeExitAction.STOP_BUYING, null, null, true, false,
                    null, 6, new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.15"),
                    null, 300, new BigDecimal("0.002"), 1200, 0, new BigDecimal("10000"),
                    null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                    GridDirection.LONG, true, 1,
                    GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER,
                    new BigDecimal("4"), 1, 3, new BigDecimal("0.05"), true, true);

            assertThat(cfg.hedgeExitMode())
                    .as("бот, заведённый до трейлинга, обязан вести эпизод как вёл")
                    .isEqualTo(GridConfig.HedgeExitMode.BREAKEVEN_TARGET);
            assertThat(cfg.hedgeTrailingOffsetPct())
                    .as("отступ подставляется всегда, чтобы включение режима не требовало второй правки")
                    .isEqualByComparingTo("0.005");
        }
    }

    // ==============================
    // ЧЕРЕЗ СТРАТЕГИЮ
    // ==============================

    /**
     * То же самое, но целиком: от пробоя и переворота до закрытия эпизода.
     *
     * Отдельно от расчётов выше, потому что проверяет другое — что стратегия зовёт
     * храповик ДО проверки цели и не держит в стакане заявку выхода, которая
     * исполнилась бы раньше, чем храповик успел бы взвестись.
     */
    @Nested
    @DisplayName("в стратегии")
    class InStrategy {

        /** Позиция 10 по себестоимости 220 против цены 20: безубыток плеча выходит сюда. */
        private static final String BREAKEVEN = "19.310344828";

        private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
        private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

        private UUID botId;
        private StrategyContext ctx;
        private ExecutionGateway gateway;
        private AtomicReference<Instant> clockNow;
        private AtomicReference<GridStrategyState> saved;

        @BeforeEach
        void setUp() {
            botId = UUID.randomUUID();
            clockNow = new AtomicReference<>(now);
            saved = new AtomicReference<>();
        }

        @Test
        @DisplayName("на безубытке эпизод не закрывается, а переходит на храповик")
        void ridesPastBreakevenInsteadOfClosing() {
            GridStrategy strategy = flippedStrategy(GridConfig.HedgeExitMode.TARGET_WITH_TRAILING);
            assertThat(episode().targetPrice()).isEqualByComparingTo(BREAKEVEN);

            strategy.onPrice(price("19.30"));

            assertThat(episode())
                    .as("цель достигнута, но эпизод обязан остаться открытым")
                    .isNotNull();
            assertThat(episode().trailingTarget())
                    .as("первая подтянутая цель — сам безубыток: хуже него храповик не встаёт")
                    .isEqualByComparingTo(BREAKEVEN);
            assertThat(lastIntentWithPurpose(OrderPurpose.RECOVERY))
                    .as("заявки выхода в стакане быть не должно — она бы и закрыла эпизод")
                    .isNull();
        }

        @Test
        @DisplayName("падение тянет цель за собой, разворот закрывает эпизод по ней")
        void ratchetFollowsTheFallAndClosesOnReversal() {
            GridStrategy strategy = flippedStrategy(GridConfig.HedgeExitMode.TARGET_WITH_TRAILING);

            strategy.onPrice(price("19.30"));
            strategy.onPrice(price("18.00"));
            assertThat(episode().trailingTarget())
                    .as("цель поехала за ценой: 18.00 плюс процент отступа")
                    .isEqualByComparingTo("18.18");

            strategy.onPrice(price("18.10"));
            assertThat(episode())
                    .as("откат мельче отступа эпизод не трогает")
                    .isNotNull();
            assertThat(episode().trailingTarget())
                    .as("и цель назад не двигает")
                    .isEqualByComparingTo("18.18");

            strategy.onPrice(price("18.20"));
            assertThat(saved.get().hedgeEpisode())
                    .as("откат до подтянутой цели закрывает эпизод")
                    .isNull();
            assertThat(lastIntentWithPurpose(OrderPurpose.RECOVERY))
                    .as("закрывается он заявкой на весь размер плеча")
                    .isNotNull();
            assertThat(lastIntentWithPurpose(OrderPurpose.RECOVERY).quantity())
                    .isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("в прежнем режиме та же цена закрывает эпизод по безубытку")
        void breakevenModeClosesAtTheStaticTarget() {
            GridStrategy strategy = flippedStrategy(GridConfig.HedgeExitMode.BREAKEVEN_TARGET);

            // Ровно тот же тик, что в трейлинге оставил эпизод открытым.
            strategy.onPrice(price("19.30"));

            assertThat(saved.get().hedgeEpisode())
                    .as("прежнее поведение: цель достигнута — эпизод закрыт")
                    .isNull();
        }

        @Test
        @DisplayName("до безубытка храповик не вмешивается ни в цель, ни в заявку выхода")
        void keepsRestingOrderUntilTargetIsReached() {
            GridStrategy strategy = flippedStrategy(GridConfig.HedgeExitMode.BREAKEVEN_TARGET);

            strategy.onPrice(price("19.50"));

            assertThat(episode().trailing()).isFalse();
            PlaceIntent exit = lastIntentWithPurpose(OrderPurpose.RECOVERY);
            assertThat(exit).as("в прежнем режиме выход стоит в стакане, как и стоял").isNotNull();
            assertThat(exit.limitPrice()).isEqualByComparingTo(episode().targetPrice());
        }

        private HedgeEpisode episode() {
            return saved.get() == null ? null : saved.get().hedgeEpisode();
        }

        /** Доводит бота до подтверждённого пробоя вниз, то есть до открытого эпизода плеча. */
        private GridStrategy flippedStrategy(GridConfig.HedgeExitMode mode) {
            GridStrategy strategy = startedWithPosition(mode);
            strategy.onPrice(price("20.0"));
            clockNow.set(clockNow.get().plusSeconds(400));
            strategy.onPrice(price("20.0"));
            assertThat(episode()).as("эпизод плеча обязан открыться").isNotNull();
            return strategy;
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

        private GridStrategy startedWithPosition(GridConfig.HedgeExitMode mode) {
            ctx = mock(StrategyContext.class);
            ExchangeClient exchange = mock(ExchangeClient.class);
            MarketDataApi marketData = mock(MarketDataApi.class);
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
                    true, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                    true, true, new BigDecimal("1000"), new BigDecimal("1000000")));
            when(ctx.exchange()).thenReturn(exchange);
            when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
            when(ctx.carryDailyRate()).thenReturn(BigDecimal.ZERO);
            when(ctx.marginAttributes()).thenReturn(Optional.empty());
            when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.of(
                    new GridStrategyState(
                            new GridRange(new BigDecimal("21.0"), new BigDecimal("23.0"), 10,
                                    GridRange.Origin.ATR_INITIAL, now),
                            1)));
            when(ctx.inventory()).thenReturn(new Inventory(
                    new BigDecimal("10"), new BigDecimal("220"), new BigDecimal("22")));
            org.mockito.Mockito.doAnswer(i -> {
                saved.set(i.getArgument(0));
                return null;
            }).when(ctx).saveState(any());

            when(exchange.marketData()).thenReturn(marketData);
            when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                    new BigDecimal("0.0005"), new BigDecimal("0.0005")));
            when(marketData.getTradingStatus(instrumentId)).thenReturn(
                    new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
            when(marketData.getLastPrice(instrumentId)).thenReturn(price("21.5"));
            // Стакан вокруг двадцати: по нему считается цена переворота, и от неё —
            // безубыток эпизода. Двигать его вслед за тиками не нужно: храповик смотрит
            // на цену, а не на стакан.
            when(marketData.getOrderBook(eq(instrumentId), anyInt()))
                    .thenAnswer(i -> new OrderBook(instrumentId, 1,
                            List.of(new OrderBookLevel(new Price(new BigDecimal("20.0"), "rub"), BigDecimal.TEN)),
                            List.of(new OrderBookLevel(new Price(new BigDecimal("20.1"), "rub"), BigDecimal.TEN)),
                            null, null, now));
            when(gateway.openOrders(botId)).thenReturn(List.of());
            when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
            when(gateway.reconcile(any())).thenReturn(reconciled("10"));

            GridStrategy strategy = new GridStrategy(config(mode));
            strategy.onStart(ctx, reconciled("10"));
            strategy.onReconcile(reconciled("10"));
            return strategy;
        }

        private ReconcileResult reconciled(String position) {
            BigDecimal p = new BigDecimal(position);
            return new ReconcileResult(List.of(), p, p, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
        }

        private GridConfig config(GridConfig.HedgeExitMode mode) {
            return new GridConfig(
                    new BigDecimal("21.0"), new BigDecimal("23.0"), 10, new BigDecimal("1"), 10,
                    GridConfig.RangeExitAction.REPLACE_LOWER, null, null, true, true,
                    null, 6, new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.15"),
                    null, 300, new BigDecimal("0.002"), 1200, 3, new BigDecimal("10000"),
                    null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                    GridDirection.LONG, true, 1,
                    GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER,
                    new BigDecimal("4"), 1, 3, new BigDecimal("0.05"), true, true,
                    mode, OFFSET);
        }
    }
}
