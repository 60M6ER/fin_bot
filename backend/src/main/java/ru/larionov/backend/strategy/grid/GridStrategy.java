package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.execution.RiskRejectedException;
import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.StrategyContext;
import ru.larionov.backend.strategy.StrategySnapshot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сеточная стратегия, только в лонг.
 *
 * Логика: покупаем на уровне i, продаём на уровне i+1. Прибыль за цикл —
 * шаг сетки минус комиссия за оборот; отсюда и проверка при старте.
 *
 * Шортов нет намеренно: продаём только то, что перед этим купили. На брокерском
 * счёте без маржи иначе и нельзя, а с маржой риск перестаёт быть ограниченным.
 *
 * Два ограничения площадки определяют весь жизненный цикл:
 * <ul>
 *   <li>GTC у T-Invest нет — заявки живут одну сессию и на открытии выставляются заново;</li>
 *   <li>события стрима теряются при разрыве — после реконнекта сначала сверка, потом действия.</li>
 * </ul>
 */
@Slf4j
public class GridStrategy implements Strategy {

    private final GridConfig cfg;

    private StrategyContext ctx;
    private GridLadder ladder;
    private GridRange activeRange;

    /**
     * Размер заявки по уровням и бюджет, от которого он посчитан.
     *
     * Пишутся ТОЛЬКО в трёх точках перестройки лесенки (старт, перестановка вверх,
     * перестановка вниз). Именно поэтому у бота с реинвестированием прибыли объём
     * заявки не «плывёт» между покупкой и её встречной продажей: обновление комиссий
     * идёт через revalidate с уже замороженным размером.
     */
    private GridSizing sizing;
    private BigDecimal activeBudget;
    private long gridGeneration;
    private boolean halted;
    private Instant upperBreakoutCandidateAt;
    private Instant lowerBreakoutCandidateAt;
    private boolean lowerBreakoutPaused;
    private boolean awaitingUpperReplacement;
    private boolean awaitingDownwardReplacement;
    private GridRange pendingDownwardRange;
    private int downwardReplacements;
    private BigDecimal realizedDownwardLoss = BigDecimal.ZERO;
    private BigDecimal downwardLossBaseline;
    private Instant lastReplacementAt;
    private BigDecimal reconciledPositionLots;
    private volatile StrategySnapshot snapshot;

    /** Последняя известная цена: нужна, чтобы решать, где покупать, а где продавать. */
    private BigDecimal lastPrice;

    /**
     * Торгуются ли сейчас лимитные заявки.
     *
     * Источник правды — стрим статуса торгов, но он присылает событие только при
     * СМЕНЕ состояния. Поэтому при старте бот дополнительно спрашивает текущий статус
     * инструмента напрямую (см. {@link #seedTradingStatus()}): иначе бот, поднятый
     * посреди сессии, просидел бы без единого ордера до следующего перехода статуса,
     * то есть часами.
     */
    private boolean limitOrdersAvailable;

    /** Выход из диапазона вниз: покупки прекращены. */
    private boolean buyingStopped;

    /** Последний тарифный снимок, по которому проверялась прибыльность сетки. */
    private FeeInfo activeFees;
    private Instant nextFeeRefreshAt;
    private boolean blockedByFees;

    /**
     * Позиция журнала разошлась с биржевой. Пока расхождение не исчезнет,
     * бот не выставляет заявок: действовать, не зная своей позиции, — это ровно
     * то, из-за чего сетка продала больше, чем купила.
     */
    private boolean positionMismatched;

    public GridStrategy(GridConfig cfg) {
        this.cfg = cfg;
    }

    // ==============================
    // LIFECYCLE
    // ==============================

    @Override
    public void onStart(StrategyContext ctx) {
        onStart(ctx, null);
    }

    @Override
    public void onStart(StrategyContext ctx, ReconcileResult initialState) {
        this.ctx = ctx;

        if (!cfg.enabled()) {
            ctx.warn("GRID: стратегия выключена в конфигурации (enabled=false)");
            return;
        }
        TradingConstraints constraints = ctx.constraints();
        RangeResolution resolution = resolveActiveRange(initialState, constraints);
        GridStrategyState restored = resolution.state();
        this.activeRange = resolution.range();
        this.gridGeneration = resolution.generation();
        this.awaitingUpperReplacement = restored != null && restored.awaitingUpperReplacement();
        this.awaitingDownwardReplacement = restored != null && restored.awaitingDownwardReplacement();
        this.pendingDownwardRange = restored == null ? null : restored.pendingRange();
        this.downwardReplacements = restored == null ? 0 : restored.downwardReplacements();
        this.realizedDownwardLoss = restored == null
                ? BigDecimal.ZERO : restored.realizedDownwardLoss();
        this.downwardLossBaseline = restored == null ? null : restored.downwardLossBaseline();
        this.lastReplacementAt = restored == null ? null : restored.lastReplacementAt();
        boolean restoredStateCleared = false;
        if (awaitingUpperReplacement
                && cfg.onUpperBreakout() != GridConfig.UpperBreakoutAction.REPLACE_UPPER) {
            awaitingUpperReplacement = false;
            restoredStateCleared = true;
        }
        if (awaitingDownwardReplacement
                && cfg.onRangeExit() != GridConfig.RangeExitAction.REPLACE_LOWER) {
            awaitingDownwardReplacement = false;
            pendingDownwardRange = null;
            downwardLossBaseline = null;
            restoredStateCleared = true;
        }
        if (awaitingDownwardReplacement && pendingDownwardRange == null) {
            throw new IllegalStateException(
                    "Сохранено ожидание перестановки вниз без подготовленного диапазона");
        }
        this.buyingStopped = awaitingUpperReplacement || awaitingDownwardReplacement;
        this.lastPrice = resolution.referencePrice();
        this.ladder = GridLadder.build(activeRange, constraints.minPriceIncrement());

        FeeInfo fees = resolveFeeInfo();
        activeFees = fees;
        nextFeeRefreshAt = ctx.clock().instant().plusSeconds(cfg.feeRefreshSeconds());

        // Стартовать с неизвестным бюджетом хуже, чем не стартовать: ошибку подхватит
        // BotRuntimeService.start и переведёт бота в ERROR, сохранив желаемое состояние.
        this.activeBudget = cfg.workingBudget(ctx.realizedPnl());

        // Отказ стартовать — осознанное решение: сетка, не окупающая комиссию,
        // будет исправно терять деньги на каждом обороте.
        this.sizing = GridValidator.validate(cfg, activeRange, ladder, constraints.minPriceIncrement(),
                fees, constraints.lot(), ctx.execution().maxCapital(), activeBudget).sizing();

        if (resolution.persist() || restoredStateCleared) {
            persistState();
        }
        updateSnapshot();

        ctx.event(BotEventType.HOUSEKEEPING,
                "GRID готов: %d уровней от %s до %s (%s), шаг %s, %s, комиссия %s%%"
                        .formatted(activeRange.levels(),
                                activeRange.lower().toPlainString(),
                                activeRange.upper().toPlainString(),
                                activeRange.origin(),
                                ladder.effectiveStep().toPlainString(),
                                sizingSummary(),
                                fees.makerRoundTripRate().multiply(BigDecimal.valueOf(100)).toPlainString()));

        seedTradingStatus();
    }

    /**
     * Подстраховка на случай запуска посреди сессии.
     *
     * Стрим статусов присылает событие только при СМЕНЕ состояния, поэтому бот,
     * поднятый посреди открытой сессии, без этого запроса просидел бы без единой
     * заявки до следующего перехода статуса, то есть часами.
     *
     * Спрашиваем статус САМОГО ИНСТРУМЕНТА, а не календарь площадок. Раньше здесь был
     * календарь, и он врал: запрос шёл без указания биржи, а признак «торги идут»
     * выставлялся, если открыта ХОТЬ ОДНА площадка из ответа. Ночью, когда нужная
     * биржа закрыта, а какая-то другая работает, бот считал сессию открытой и до утра
     * долбился в биржу заявками, ловя отказы на каждом тике.
     *
     * Не блокирует старт при ошибке: статус всё равно подтвердится стримом при
     * ближайшей смене состояния.
     */
    private void seedTradingStatus() {
        try {
            TradingStatusEvent status = ctx.exchange().marketData()
                    .getTradingStatus(ctx.execution().instrumentId());
            if (status == null) {
                return;
            }

            limitOrdersAvailable = status.limitOrdersAvailable();
            ctx.event(BotEventType.HOUSEKEEPING, limitOrdersAvailable
                    ? "Биржа принимает лимитные заявки (%s) — сетка расставится по первой цене"
                            .formatted(status.rawStatus())
                    : "Биржа сейчас не принимает лимитные заявки (%s) — жду открытия торгов"
                            .formatted(status.rawStatus()));

        } catch (Exception e) {
            log.warn("Не удалось получить торговый статус инструмента при старте: {}", e.getMessage());
        }
    }

    /**
     * Пока биржа закрыта — переспрашиваем её статус на каждом тике.
     *
     * Иначе бот, поднятый ночью, зависит от единственного события стрима об открытии
     * сессии. Стрим события теряет (на этом построена вся логика реконнекта здесь),
     * и потерянное открытие означало бы, что бот молчит весь торговый день — отказ
     * куда неприятнее лишнего запроса.
     *
     * Обратное направление переспрашивать не нужно: пока заявки ставятся, о закрытии
     * сессии сообщит стрим, а в худшем случае биржа просто отклонит заявку.
     */
    private void refreshTradingStatusIfClosed() {
        if (limitOrdersAvailable) {
            return;
        }
        try {
            TradingStatusEvent status = ctx.exchange().marketData()
                    .getTradingStatus(ctx.execution().instrumentId());
            if (status != null && status.limitOrdersAvailable()) {
                // Через общий обработчик: он сам напишет событие и расставит сетку.
                onTradingStatus(status);
            }
        } catch (Exception e) {
            log.debug("Не удалось переспросить торговый статус: {}", e.getMessage());
        }
    }

    @Override
    public void onStop() {
        this.ladder = null;
        this.activeRange = null;
        this.snapshot = null;
        this.ctx = null;
    }

    private RangeResolution resolveActiveRange(ReconcileResult initialState, TradingConstraints constraints) {
        if (!cfg.autoRange()) {
            return new RangeResolution(GridRange.manual(cfg, ctx.clock().instant()), 0, null, false, null);
        }

        Optional<GridStrategyState> restored = ctx.loadState(GridStrategyState.class);
        if (restored.isPresent() && restored.get().activeRange() != null) {
            GridStrategyState state = restored.get();
            return new RangeResolution(state.activeRange(), Math.max(1, state.generation()), null, false, state);
        }

        BigDecimal position = initialState == null ? null : initialState.positionLots();
        if (position == null) {
            throw new IllegalStateException(
                    "Автодиапазон нельзя создать без стартовой сверки позиции с биржей");
        }
        if (position.signum() != 0) {
            throw new IllegalStateException(
                    ("У бота есть позиция %s лот(ов), но сохранённый диапазон отсутствует. "
                            + "Автоматически пересчитывать уровни опасно: встречные продажи потеряют цены.")
                            .formatted(position.toPlainString()));
        }

        VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimate(
                ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                constraints.minPriceIncrement(), ctx.clock().instant());
        ctx.event(BotEventType.HOUSEKEEPING,
                "ATR %s по %d свечам: %s; стартовый диапазон %s..%s"
                        .formatted(cfg.atrInterval(), estimate.atr().candlesUsed(),
                                estimate.atr().value().toPlainString(),
                                estimate.range().lower().toPlainString(),
                                estimate.range().upper().toPlainString()));
        return new RangeResolution(estimate.range(), 1, estimate.referencePrice(), true, null);
    }

    // ==============================
    // EVENTS
    // ==============================

    /**
     * Главный триггер жизненного цикла. У T-Invest нет GTC: вчерашние заявки мертвы,
     * поэтому открытие торгов означает «сверься и расставь сетку заново».
     */
    @Override
    public void onTradingStatus(TradingStatusEvent event) {
        if (!isReady()) {
            return;
        }

        boolean was = limitOrdersAvailable;
        limitOrdersAvailable = event.limitOrdersAvailable();

        if (!was && limitOrdersAvailable) {
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Торги открыты (%s) — сверяюсь и расставляю сетку".formatted(event.rawStatus()));
            ReconcileResult reconciled = reconcileAndCheck();
            if (awaitingDownwardReplacement) {
                manageDownwardLiquidation();
            } else {
                ensureOrders(reconciled);
            }
        } else if (was && !limitOrdersAvailable) {
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Торги закрыты (%s) — заявки истекут в конце сессии".formatted(event.rawStatus()));
        }
    }

    /**
     * Сердце сетки: исполнение порождает встречную заявку.
     * Купили на уровне i — продаём на i+1. Продали на j — уровень j-1 снова свободен
     * и будет откуплен на следующем проходе.
     */
    @Override
    public void onOrderUpdate(BotOrderView order) {
        if (!isReady() || order == null) {
            return;
        }
        if (awaitingDownwardReplacement) {
            reconcileAndCheck();
            manageDownwardLiquidation();
            return;
        }
        if (order.gridLevel() == null) {
            return;
        }
        // Реагируем на завершённое исполнение: частичное догонится следующим
        // событием по тому же clientOrderId.
        if (order.status() != OrderStatus.FILLED) {
            return;
        }

        if (order.side() == OrderSide.BUY) {
            placeCounterSell(order);
        } else {
            // Цикл закрыт — прибыль зафиксирована. Уровень ниже освободился под новую покупку.
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Цикл закрыт на уровне %d, уровень %d свободен"
                            .formatted(order.gridLevel(), order.gridLevel() - 1));
            if (awaitingUpperReplacement) {
                tryCompleteUpperReplacement();
            } else {
                ensureOrders(null);
            }
        }
    }

    /**
     * Встречная продажа сразу после покупки — именно здесь возникает прибыль сетки.
     * Ставим на следующий уровень вверх: разница и есть заработок за вычетом комиссии.
     */
    private void placeCounterSell(BotOrderView filledBuy) {
        int buyLevel = filledBuy.gridLevel();
        // Цена — уровнем выше, а grid_level продажи — это закрываемый уровень ПОКУПКИ.
        // Единое правило для обеих веток: иначе уровень не считается занятым.
        BigDecimal price = ladder.priceAt(buyLevel + 1);

        if (price == null) {
            // Купили на верхнем уровне — продавать выше некуда.
            ctx.warn("Покупка на верхнем уровне сетки (%d): встречной продажи нет".formatted(buyLevel));
            return;
        }

        // Сколько лотов этого уровня уже выставлено на продажу. Считаем именно лоты:
        // проверка «есть ли хоть одна заявка» не давала повторно продать уровень,
        // на котором лежит несколько лотов, а её отсутствие — наоборот, плодило дубли.
        long covered = ctx.gateway().openOrders(ctx.botId()).stream()
                .filter(o -> o.side() == OrderSide.SELL
                        && o.gridLevel() != null && o.gridLevel() == buyLevel)
                .mapToLong(BotOrderView::remainingLots)
                .sum();

        long held = computeHeldLotsByLevel().getOrDefault(buyLevel, 0L);
        if (held - covered < sizing.lotsAt(buyLevel)) {
            return;
        }

        place(OrderSide.SELL, price, buyLevel);
    }

    @Override
    public void onPrice(LastPrice price) {
        if (!isReady() || price == null || price.price() == null) {
            return;
        }
        lastPrice = price.price().value();

        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (processUpperBreakout()) {
            return;
        }
        if (processLowerBreakout()) {
            return;
        }
        if (checkRangeExit()) {
            return;
        }
        ensureOrders(null);
    }

    /**
     * После разрыва стрима состояние могло уйти вперёд без нас.
     * Сверку уже сделал хендлер — здесь только пересобираем заявки.
     */
    @Override
    public void onStreamReconnect() {
        if (!isReady()) {
            return;
        }
        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (awaitingUpperReplacement && tryCompleteUpperReplacement()) {
            return;
        }
        ensureOrders(null);
    }

    @Override
    public void onTick() {
        if (!isReady()) {
            return;
        }
        // Сторож: периодическая сверка на случай, если стрим молчит не потому,
        // что рынок спокоен, а потому что мы ослепли.
        ReconcileResult reconciled = reconcileAndCheck();

        // Тот же сторож для торговой сессии: потерянное стримом открытие иначе
        // означало бы, что бот молчит весь день.
        refreshTradingStatusIfClosed();

        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (lastPrice != null && processUpperBreakout()) {
            return;
        }
        if (lastPrice != null && processLowerBreakout()) {
            return;
        }
        if (lastPrice != null && checkRangeExit()) {
            return;
        }
        ensureOrders(reconciled);
    }

    /**
     * Сверка с биржей и контроль расхождения позиции.
     *
     * Расхождение означает, что мы не знаем своей реальной позиции. Торговать в таком
     * состоянии нельзя: именно это позволило ошибке пересчёта лотов в штуки
     * превратиться в лишние заявки на продажу. Флаг снимается сам, как только
     * позиция сойдётся, — вмешательство не требуется.
     */
    private ReconcileResult reconcileAndCheck() {
        ReconcileResult reconciled = ctx.gateway().reconcile(ctx.execution());
        onReconcile(reconciled);
        return reconciled;
    }

    /**
     * Единая точка обработки итога сверки — чьей угодно.
     *
     * Раньше флаг расхождения выставлялся только здешней сверкой, а стартовую и
     * послереконнектную делал хендлер, и их результат сюда не доходил. Из-за этого
     * бот, поднятый с расхождением в 5 лотов, честно записал его в журнал событий
     * и тут же выставил продажи на позицию, которой на бирже не существовало.
     */
    @Override
    public void onReconcile(ReconcileResult reconciled) {
        if (reconciled == null || ctx == null) {
            return;
        }

        BigDecimal mismatch = reconciled.positionMismatch();
        boolean mismatched = mismatch != null && mismatch.signum() != 0;

        if (mismatched && !positionMismatched) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    ("Позиция журнала расходится с биржей на %s лот(ов). Торговля приостановлена "
                            + "до устранения расхождения — выставлять заявки, не зная своей позиции, опасно.")
                            .formatted(mismatch.toPlainString()));
        } else if (!mismatched && positionMismatched) {
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Позиция сошлась с биржей — торговля возобновляется");
        }

        positionMismatched = mismatched;
        reconciledPositionLots = reconciled.positionLots();

        if (!positionMismatched && awaitingUpperReplacement) {
            tryCompleteUpperReplacement();
        }
        if (!positionMismatched && awaitingDownwardReplacement
                && reconciledPositionLots != null && reconciledPositionLots.signum() == 0) {
            tryCompleteDownwardReplacement();
        }
    }

    // ==============================
    // CORE
    // ==============================

    /**
     * Приводит выставленные заявки в соответствие с состоянием.
     *
     * Порядок намеренный: сначала продажи (высвобождают капитал и фиксируют прибыль),
     * потом покупки. Так лимит капитала не блокирует фиксацию уже заработанного.
     */
    private void ensureOrders(ReconcileResult reconciled) {
        if (!limitOrdersAvailable) {
            return;
        }
        if (lastPrice == null) {
            return;
        }
        if (positionMismatched) {
            // Не знаем свою позицию — не торгуем. Ровно отсутствие этой проверки
            // позволило ошибке пересчёта лотов превратиться в лишние заявки:
            // сверка честно сообщала о расхождении, а бот продолжал работать.
            return;
        }
        refreshFeesIfNeeded();

        List<BotOrderView> open = ctx.gateway().openOrders(ctx.botId());
        Map<Integer, BotOrderView> openBuys = new HashMap<>();

        // Продажи считаем В ЛОТАХ, а не «одна заявка на уровень». На уровне их может
        // висеть несколько, и Map<уровень, заявка> вторую молча затирала: покрытие
        // всегда выглядело как один лот, разница «куплено минус покрыто» не убывала,
        // и каждый проход добавлял ещё одну продажу — до упора в лимит частоты.
        Map<Integer, Long> openSellLotsByLevel = new HashMap<>();
        int openOrderCount = 0;

        for (BotOrderView o : open) {
            if (o.gridLevel() == null) {
                continue;
            }
            openOrderCount++;
            if (o.side() == OrderSide.BUY) {
                openBuys.put(o.gridLevel(), o);
            } else {
                openSellLotsByLevel.merge(o.gridLevel(), o.remainingLots(), Long::sum);
            }
        }

        Map<Integer, Long> heldByLevel = computeHeldLotsByLevel();

        openOrderCount += placeMissingSells(openSellLotsByLevel, heldByLevel, openOrderCount);
        if (!buyingStopped) {
            placeMissingBuys(openBuys, openSellLotsByLevel, heldByLevel, openOrderCount);
        }
    }

    /**
     * Сколько купленного и ещё не проданного лежит на каждом уровне.
     *
     * Считается по журналу: исполненные покупки уровня минус исполненные продажи,
     * этот уровень закрывающие. По одним открытым заявкам это не выводится —
     * исполненная покупка из них исчезает, и уровень выглядел бы свободным.
     */
    private Map<Integer, Long> computeHeldLotsByLevel() {
        Map<Integer, Long> held = new HashMap<>();

        for (BotOrderView o : ctx.gateway().recentOrders(ctx.botId())) {
            if (o.gridLevel() == null || o.executedLots() <= 0) {
                continue;
            }
            long delta = o.side() == OrderSide.BUY ? o.executedLots() : -o.executedLots();
            held.merge(o.gridLevel(), delta, Long::sum);
        }

        held.values().removeIf(v -> v <= 0);
        return held;
    }

    /**
     * Купленное, но не выставленное на продажу.
     *
     * Продажа привязана к уровню покупки, который закрывает: её grid_level — это
     * уровень ПОКУПКИ, а цена берётся уровнем выше. Раньше эта ветка ставила продажи
     * по своему правилу («ближайший свободный уровень выше цены»), несовместимому
     * с правилом встречной продажи, — из-за чего уровень покупки не считался занятым
     * и выкупался снова и снова.
     */
    private int placeMissingSells(Map<Integer, Long> openSellLotsByLevel,
                                  Map<Integer, Long> heldByLevel,
                                  int openOrderCount) {
        int placed = 0;

        for (Map.Entry<Integer, Long> entry : heldByLevel.entrySet()) {
            int buyLevel = entry.getKey();
            long heldLots = entry.getValue();

            long covered = openSellLotsByLevel.getOrDefault(buyLevel, 0L);
            if (heldLots - covered < sizing.lotsAt(buyLevel)) {
                continue;
            }
            // Лимит активных заявок распространяется и на продажи. Раньше он проверялся
            // только для покупок, и разгон по продажам ничем не ограничивался.
            if (openOrderCount + placed >= cfg.maxActiveOrders()) {
                return placed;
            }

            BigDecimal price = ladder.priceAt(buyLevel + 1);
            if (price == null) {
                ctx.warn("Куплено на верхнем уровне сетки (%d) — продавать выше некуда".formatted(buyLevel));
                continue;
            }
            if (!place(OrderSide.SELL, price, buyLevel)) {
                return placed;
            }
            openSellLotsByLevel.merge(buyLevel, sizing.lotsAt(buyLevel), Long::sum);
            placed++;
        }
        return placed;
    }

    /** Покупки на свободных уровнях ниже цены, ближайшие к рынку — первыми. */
    private void placeMissingBuys(Map<Integer, BotOrderView> openBuys,
                                  Map<Integer, Long> openSellLotsByLevel,
                                  Map<Integer, Long> heldByLevel,
                                  int activeCount) {
        int startLevel = ladder.highestLevelBelow(lastPrice);

        for (int level = startLevel; level >= 0; level--) {
            if (activeCount >= cfg.maxActiveOrders()) {
                return;
            }
            if (openBuys.containsKey(level)) {
                continue;
            }
            // Уровень занят, если на нём висит наша продажа ИЛИ на нём лежит
            // непроданное купленное. Второе условие и отсутствовало: без него
            // исполненная покупка освобождала уровень под новую покупку,
            // и позиция росла без ограничений.
            if (openSellLotsByLevel.containsKey(level) || heldByLevel.containsKey(level)) {
                continue;
            }
            BigDecimal price = ladder.priceAt(level);
            if (price == null || price.compareTo(activeRange.lower()) < 0) {
                continue;
            }
            if (!place(OrderSide.BUY, price, level)) {
                return;
            }
            openBuys.put(level, null);
            activeCount++;
        }
    }

    /**
     * @return false, если ставить дальше нет смысла (упёрлись в лимит)
     */
    private boolean place(OrderSide side, BigDecimal price, int level) {
        long lots = sizing.lotsAt(level);
        if (lots <= 0) {
            // Уровень не профинансирован бюджетом (в бюджетных режимах так выглядит
            // верхний, продажный уровень). Ноль до PlaceIntent доходить не должен.
            return true;
        }
        try {
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(side, lots, price, level));
            return true;
        } catch (RiskRejectedException e) {
            // Штатный отказ: лимит сработал так, как задумано.
            ctx.event(BotEventType.RISK_BLOCKED, e.getMessage());
            return false;
        } catch (Exception e) {
            // Сетевая ошибка: запись осталась PENDING, её разрешит сверка.
            // Продолжать этот проход бессмысленно — биржа недоступна.
            ctx.error("Не удалось выставить заявку на уровне " + level, e);
            return false;
        }
    }

    /**
     * Подтверждает выход вверх и переводит сетку в режим распродажи позиции.
     * Возвращает true, пока старую сетку нельзя пополнять новыми заявками.
     */
    private boolean processUpperBreakout() {
        if (cfg.onUpperBreakout() != GridConfig.UpperBreakoutAction.REPLACE_UPPER) {
            return false;
        }
        if (positionMismatched) {
            upperBreakoutCandidateAt = null;
            return awaitingUpperReplacement;
        }

        Instant now = ctx.clock().instant();
        if (awaitingUpperReplacement) {
            if (lastPrice.compareTo(activeRange.upper()) <= 0) {
                awaitingUpperReplacement = false;
                upperBreakoutCandidateAt = null;
                halted = false;
                buyingStopped = shouldStopBuying();
                persistState();
                updateSnapshot();
                ctx.event(BotEventType.HOUSEKEEPING,
                        "Цена вернулась в прежний диапазон до его замены. Перестановка вверх отменена.");
                return false;
            }
            cancelOpenBuys();
            if (!tryCompleteUpperReplacement()) {
                ensureOrders(null);
            }
            return true;
        }

        if (lastReplacementAt != null
                && now.isBefore(lastReplacementAt.plusSeconds(cfg.replaceCooldownSeconds()))) {
            upperBreakoutCandidateAt = null;
            return false;
        }

        BigDecimal margin = activeRange.upper().multiply(cfg.breakoutMarginPct())
                .max(ladder.effectiveStep().divide(BigDecimal.valueOf(2)));
        BigDecimal threshold = activeRange.upper().add(margin);
        if (lastPrice.compareTo(activeRange.upper()) <= 0) {
            upperBreakoutCandidateAt = null;
            return false;
        }
        if (lastPrice.compareTo(threshold) < 0) {
            return false;
        }

        if (upperBreakoutCandidateAt == null) {
            upperBreakoutCandidateAt = now;
            ctx.event(BotEventType.HOUSEKEEPING,
                    "GRID: цена %s выше порога пробоя %s, начато подтверждение"
                            .formatted(lastPrice.toPlainString(), threshold.toPlainString()));
            return false;
        }
        if (Duration.between(upperBreakoutCandidateAt, now).getSeconds()
                < cfg.breakoutConfirmSeconds()) {
            return false;
        }

        awaitingUpperReplacement = true;
        upperBreakoutCandidateAt = null;
        buyingStopped = true;
        cancelOpenBuys();
        persistState();
        updateSnapshot();
        ctx.event(BotEventType.RANGE_EXIT,
                ("Пробой верхней границы %s подтверждён по цене %s. Покупки сняты; "
                        + "жду закрытия позиции продажами перед перестановкой.")
                        .formatted(activeRange.upper().toPlainString(), lastPrice.toPlainString()));
        if (!tryCompleteUpperReplacement()) {
            ensureOrders(null);
        }
        return true;
    }

    /** Нижний пробой сначала подтверждается, затем проходит бюджет и валидацию диапазона. */
    private boolean processLowerBreakout() {
        if (cfg.onRangeExit() != GridConfig.RangeExitAction.REPLACE_LOWER) {
            return false;
        }
        if (positionMismatched) {
            lowerBreakoutCandidateAt = null;
            return false;
        }

        Instant now = ctx.clock().instant();
        if (lastPrice.compareTo(activeRange.lower()) >= 0) {
            lowerBreakoutCandidateAt = null;
            if (lowerBreakoutPaused) {
                lowerBreakoutPaused = false;
                buyingStopped = shouldStopBuying();
                updateSnapshot();
            }
            return false;
        }

        BigDecimal margin = activeRange.lower().multiply(cfg.breakoutMarginPct())
                .max(ladder.effectiveStep().divide(BigDecimal.valueOf(2)));
        BigDecimal threshold = activeRange.lower().subtract(margin);
        if (lastPrice.compareTo(threshold) > 0) {
            return false;
        }

        lowerBreakoutPaused = true;
        buyingStopped = true;
        cancelOpenBuys();
        updateSnapshot();

        if (lastReplacementAt != null
                && now.isBefore(lastReplacementAt.plusSeconds(cfg.replaceCooldownSeconds()))) {
            return true;
        }
        if (lowerBreakoutCandidateAt == null) {
            lowerBreakoutCandidateAt = now;
            ctx.event(BotEventType.HOUSEKEEPING,
                    "GRID: цена %s ниже порога пробоя %s, начато подтверждение"
                            .formatted(lastPrice.toPlainString(), threshold.toPlainString()));
            return true;
        }
        if (Duration.between(lowerBreakoutCandidateAt, now).getSeconds()
                < cfg.breakoutConfirmSeconds()) {
            return true;
        }

        lowerBreakoutCandidateAt = null;
        beginDownwardReplacement();
        return true;
    }

    private void beginDownwardReplacement() {
        if (downwardReplacements >= cfg.maxDownwardReplacements()) {
            stopPermanently("Исчерпан лимит перестановок вниз: %d из %d"
                    .formatted(downwardReplacements, cfg.maxDownwardReplacements()));
            return;
        }

        ReconcileResult fresh = reconcileAndCheck();
        if (positionMismatched || fresh.positionLots() == null) {
            failLowerReplacement(
                    "Не удалось получить достоверную позицию перед перестановкой вниз");
            return;
        }

        try {
            if (reconciledPositionLots.signum() > 0) {
                enforceDownwardBudget(bestBid());
                if (halted) {
                    return;
                }
            }
        } catch (Exception e) {
            failLowerReplacement("Не удалось оценить бюджет закрытия позиции: " + e.getMessage());
            return;
        }

        GridRange candidate;
        GridLadder candidateLadder;
        try {
            VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimateAround(
                    ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                    ctx.constraints().minPriceIncrement(), ctx.clock().instant(), lastPrice,
                    GridRange.Origin.ATR_REPLACED_DOWN);
            candidate = estimate.range();
            candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());
            // Размер здесь отбрасываем: он будет пересчитан при коммите перестановки,
            // когда убыток ликвидации уже зафиксирован. Проверка нужна, чтобы отвергнуть
            // нефинансируемый кандидат ДО распродажи позиции, а не после неё.
            GridValidator.validate(cfg, candidate, candidateLadder, ctx.constraints().minPriceIncrement(),
                    activeFees, ctx.constraints().lot(), ctx.execution().maxCapital(),
                    cfg.workingBudget(ctx.realizedPnl()));
        } catch (Exception e) {
            failLowerReplacement("Новый нижний диапазон не прошёл проверку: " + e.getMessage());
            return;
        }

        pendingDownwardRange = candidate;
        try {
            downwardLossBaseline = ctx.realizedPnl();
        } catch (Exception e) {
            pendingDownwardRange = null;
            failLowerReplacement("Не удалось зафиксировать исходный P/L: " + e.getMessage());
            return;
        }
        awaitingDownwardReplacement = true;
        buyingStopped = true;
        try {
            persistState();
        } catch (Exception e) {
            awaitingDownwardReplacement = false;
            pendingDownwardRange = null;
            downwardLossBaseline = null;
            failLowerReplacement("Не удалось сохранить начало ликвидации: " + e.getMessage());
            return;
        }
        updateSnapshot();

        int cancelled = 0;
        try {
            cancelled = ctx.gateway().cancelAll(ctx.execution());
        } catch (Exception e) {
            ctx.error("Не удалось одним запросом снять заявки перед ликвидацией", e);
        }
        ctx.event(BotEventType.RANGE_EXIT,
                ("Пробой нижней границы %s подтверждён по цене %s. Снято заявок: %d; "
                        + "начинаю контролируемое закрытие позиции перед диапазоном %s..%s.")
                        .formatted(activeRange.lower().toPlainString(), lastPrice.toPlainString(), cancelled,
                                candidate.lower().toPlainString(), candidate.upper().toPlainString()));

        manageDownwardLiquidation();
    }

    /** Поддерживает одну агрессивную SELL на фактический остаток позиции. */
    private void manageDownwardLiquidation() {
        if (!awaitingDownwardReplacement || halted || positionMismatched
                || reconciledPositionLots == null) {
            return;
        }
        if (reconciledPositionLots.signum() == 0) {
            for (BotOrderView order : ctx.gateway().openOrders(ctx.botId())) {
                try {
                    ctx.gateway().cancel(ctx.execution(), order.id());
                } catch (Exception e) {
                    ctx.error("Не удалось снять остаточную заявку после ликвидации", e);
                }
            }
            if (ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
                tryCompleteDownwardReplacement();
            }
            return;
        }
        if (reconciledPositionLots.signum() < 0) {
            stopPermanently("Сверка показала короткую позицию во время ликвидации: "
                    + reconciledPositionLots.toPlainString());
            return;
        }
        if (!limitOrdersAvailable) {
            return;
        }

        BigDecimal bid;
        try {
            bid = bestBid();
            enforceDownwardBudget(bid);
        } catch (Exception e) {
            ctx.error("Не удалось обновить лучший бид для ликвидации", e);
            return;
        }
        if (halted) {
            return;
        }
        placeOrRepriceLiquidation(bid);
    }

    private void placeOrRepriceLiquidation(BigDecimal bid) {
        List<BotOrderView> open = ctx.gateway().openOrders(ctx.botId());
        BotOrderView liquidation = open.stream()
                .filter(o -> o.side() == OrderSide.SELL && o.gridLevel() == null)
                .findFirst()
                .orElse(null);

        boolean onlyLiquidation = liquidation != null && open.size() == 1;
        if (onlyLiquidation && liquidation.limitPrice() != null
                && liquidation.limitPrice().compareTo(bid) <= 0
                && liquidation.remainingLots() == reconciledPositionLots.longValueExact()) {
            return;
        }

        for (BotOrderView order : open) {
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять заявку перед переоценкой ликвидации", e);
            }
        }
        ReconcileResult afterCancel = reconcileAndCheck();
        if (positionMismatched || afterCancel.positionLots() == null
                || afterCancel.positionLots().signum() <= 0) {
            manageDownwardLiquidation();
            return;
        }
        if (!ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
            return;
        }

        BigDecimal freshBid;
        try {
            freshBid = bestBid();
            enforceDownwardBudget(freshBid);
        } catch (Exception e) {
            ctx.error("Не удалось обновить лучший бид после снятия ликвидационной заявки", e);
            return;
        }
        if (halted) {
            return;
        }
        try {
            long lots = afterCancel.positionLots().longValueExact();
            ctx.gateway().placeLimit(ctx.execution(),
                    new PlaceIntent(OrderSide.SELL, lots, freshBid, null));
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Ликвидационная SELL: %d лот(ов) по лучшему биду %s"
                            .formatted(lots, freshBid.toPlainString()));
        } catch (RiskRejectedException e) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Ликвидационная заявка пока запрещена лимитом: " + e.getMessage());
        } catch (Exception e) {
            ctx.error("Не удалось выставить ликвидационную заявку", e);
        }
    }

    private void enforceDownwardBudget(BigDecimal bid) {
        BigDecimal projected = projectedDownwardLoss(bid);
        if (projected.compareTo(cfg.maxRealizedLoss()) > 0) {
            stopPermanently(("Перестановка вниз остановлена бюджетом убытка: прогноз %s, "
                    + "потолок %s").formatted(projected.toPlainString(),
                    cfg.maxRealizedLoss().toPlainString()));
        }
    }

    private BigDecimal projectedDownwardLoss(BigDecimal bid) {
        Inventory inventory = ctx.inventory();
        long position = reconciledPositionLots == null ? 0 : reconciledPositionLots.longValueExact();
        if (inventory.openLots() != position) {
            stopPermanently(("Позиция книги %d лот(ов) не совпадает со сверенной позицией %d. "
                    + "Автоматически фиксировать убыток нельзя.")
                    .formatted(inventory.openLots(), position));
            return cfg.maxRealizedLoss().add(BigDecimal.ONE);
        }

        BigDecimal currentLoss = downwardLossBaseline == null
                ? BigDecimal.ZERO
                : downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO);
        BigDecimal gross = bid.multiply(BigDecimal.valueOf(position))
                .multiply(BigDecimal.valueOf(Math.max(1, ctx.constraints().lot())));
        BigDecimal sellFee = gross.multiply(activeFees.makerSellRate());
        BigDecimal remainingLoss = inventory.costBasisOpen()
                .subtract(gross).add(sellFee).max(BigDecimal.ZERO);
        return realizedDownwardLoss.add(currentLoss).add(remainingLoss);
    }

    private BigDecimal bestBid() {
        OrderBook book = ctx.exchange().marketData()
                .getOrderBook(ctx.execution().instrumentId(), 1);
        BigDecimal bid = book == null || book.bids() == null || book.bids().isEmpty()
                || book.bids().get(0).price() == null
                ? null : book.bids().get(0).price().value();
        if (bid == null || bid.signum() <= 0) {
            throw new IllegalStateException("Биржа не вернула лучший бид для закрытия позиции");
        }
        return bid;
    }

    private void tryCompleteDownwardReplacement() {
        if (!awaitingDownwardReplacement || positionMismatched || pendingDownwardRange == null
                || reconciledPositionLots == null || reconciledPositionLots.signum() != 0
                || !ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
            return;
        }

        BigDecimal completedLoss = downwardLossBaseline == null
                ? BigDecimal.ZERO
                : downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO);
        realizedDownwardLoss = realizedDownwardLoss.add(completedLoss);
        if (realizedDownwardLoss.compareTo(cfg.maxRealizedLoss()) > 0) {
            downwardLossBaseline = null;
            stopPermanently(("Фактический накопленный убыток %s превысил потолок %s")
                    .formatted(realizedDownwardLoss.toPlainString(),
                            cfg.maxRealizedLoss().toPlainString()));
            return;
        }

        GridRange previous = activeRange;
        GridLadder previousLadder = ladder;
        GridSizing previousSizing = sizing;
        BigDecimal previousBudget = activeBudget;
        long previousGeneration = gridGeneration;
        int previousDownwardReplacements = downwardReplacements;
        BigDecimal previousRealizedDownwardLoss = realizedDownwardLoss.subtract(completedLoss);
        BigDecimal previousLossBaseline = downwardLossBaseline;
        Instant previousReplacementAt = lastReplacementAt;
        GridRange candidate = pendingDownwardRange;

        GridLadder candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());
        GridSizing candidateSizing;
        BigDecimal candidateBudget;
        try {
            // Пересчёт обязателен именно здесь: принудительная ликвидация только что
            // зафиксировала убыток, и при реинвестировании прибыли рабочий бюджет стал
            // меньше. Переиспользование старого размера означало бы перерасход бюджета.
            candidateBudget = cfg.workingBudget(ctx.realizedPnl());
            candidateSizing = GridValidator.validate(cfg, candidate, candidateLadder,
                    ctx.constraints().minPriceIncrement(), activeFees, ctx.constraints().lot(),
                    ctx.execution().maxCapital(), candidateBudget).sizing();
        } catch (Exception e) {
            failLowerReplacement("Новый нижний диапазон не прошёл проверку: " + e.getMessage());
            return;
        }

        activeRange = candidate;
        ladder = candidateLadder;
        sizing = candidateSizing;
        activeBudget = candidateBudget;
        gridGeneration++;
        downwardReplacements++;
        lastReplacementAt = ctx.clock().instant();
        awaitingDownwardReplacement = false;
        pendingDownwardRange = null;
        downwardLossBaseline = null;
        lowerBreakoutPaused = false;
        buyingStopped = shouldStopBuying();
        halted = false;
        try {
            persistState();
        } catch (Exception e) {
            activeRange = previous;
            ladder = previousLadder;
            sizing = previousSizing;
            activeBudget = previousBudget;
            gridGeneration = previousGeneration;
            downwardReplacements = previousDownwardReplacements;
            realizedDownwardLoss = previousRealizedDownwardLoss;
            downwardLossBaseline = previousLossBaseline;
            lastReplacementAt = previousReplacementAt;
            awaitingDownwardReplacement = true;
            pendingDownwardRange = candidate;
            failLowerReplacement("Не удалось сохранить новый нижний диапазон: " + e.getMessage());
            return;
        }

        updateSnapshot();
        String note = ("GRID поколение %d: диапазон %s..%s заменён вниз на %s..%s; "
                + "убыток перестановки %s, накоплено %s, использовано %d из %d")
                .formatted(gridGeneration,
                        previous.lower().toPlainString(), previous.upper().toPlainString(),
                        activeRange.lower().toPlainString(), activeRange.upper().toPlainString(),
                        completedLoss.toPlainString(), realizedDownwardLoss.toPlainString(),
                        downwardReplacements, cfg.maxDownwardReplacements());
        try {
            ctx.ledgerMarker(LedgerEntryType.GRID_REPLACED, note);
        } catch (Exception e) {
            ctx.error("Диапазон заменён, но отметку GRID_REPLACED не удалось записать в книгу", e);
        }
        ctx.event(BotEventType.GRID_REPLACED, note);
        ensureOrders(null);
    }

    private void failLowerReplacement(String reason) {
        halted = true;
        buyingStopped = true;
        updateSnapshot();
        ctx.event(BotEventType.RISK_BLOCKED,
                reason + ". Старая сетка сохранена, покупки остановлены.");
    }

    private void stopPermanently(String reason) {
        if (halted) {
            return;
        }
        if (awaitingDownwardReplacement && downwardLossBaseline != null) {
            try {
                realizedDownwardLoss = realizedDownwardLoss.add(
                        downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO));
            } catch (Exception e) {
                ctx.error("Не удалось уточнить уже реализованный убыток при остановке", e);
            }
        }
        halted = true;
        buyingStopped = true;
        awaitingDownwardReplacement = false;
        pendingDownwardRange = null;
        downwardLossBaseline = null;
        try {
            ctx.gateway().cancelAll(ctx.execution());
        } catch (Exception e) {
            ctx.error("Не удалось снять заявки при аварийной остановке", e);
        }
        try {
            persistState();
        } catch (Exception e) {
            ctx.error("Не удалось сохранить причину аварийной остановки", e);
        }
        updateSnapshot();
        try {
            ctx.event(BotEventLevel.ERROR, BotEventType.RANGE_EXIT,
                    reason + ". Бот выключается; оставшуюся позицию проверьте вручную.");
        } catch (Exception e) {
            log.error("Не удалось записать событие аварийной остановки: {}", e.getMessage(), e);
        }
        ctx.requestStop(reason);
    }

    private boolean shouldStopBuying() {
        return blockedByFees || halted || awaitingUpperReplacement
                || awaitingDownwardReplacement || lowerBreakoutPaused;
    }

    /** Снимает только покупки старой сетки; закрывающие продажи обязаны остаться. */
    private void cancelOpenBuys() {
        for (BotOrderView order : ctx.gateway().openOrders(ctx.botId())) {
            if (order.side() != OrderSide.BUY) {
                continue;
            }
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять покупку " + order.clientOrderId()
                        + " перед перестановкой диапазона", e);
            }
        }
    }

    private boolean tryCompleteUpperReplacement() {
        if (!awaitingUpperReplacement || halted || positionMismatched || lastPrice == null
                || reconciledPositionLots == null || reconciledPositionLots.signum() != 0) {
            return false;
        }

        cancelOpenBuys();
        if (!ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
            return false;
        }

        GridRange previous = activeRange;
        GridLadder previousLadder = ladder;
        GridSizing previousSizing = sizing;
        BigDecimal previousBudget = activeBudget;
        GridRange candidate;
        GridLadder candidateLadder;
        GridSizing candidateSizing;
        BigDecimal candidateBudget;
        try {
            VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimateAround(
                    ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                    ctx.constraints().minPriceIncrement(), ctx.clock().instant(), lastPrice,
                    GridRange.Origin.ATR_REPLACED_UP);
            candidate = estimate.range();
            candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());

            // Перестройка сетки — одна из трёх точек, где размер заявки пересчитывается:
            // цены уровней изменились, значит изменилось и то, сколько лотов помещается в бюджет.
            candidateBudget = cfg.workingBudget(ctx.realizedPnl());

            // До этой точки старая сетка и её checkpoint не меняются.
            candidateSizing = GridValidator.validate(cfg, candidate, candidateLadder,
                    ctx.constraints().minPriceIncrement(), activeFees, ctx.constraints().lot(),
                    ctx.execution().maxCapital(), candidateBudget).sizing();

        } catch (Exception e) {
            failUpperReplacement("Новый диапазон не прошёл проверку: " + e.getMessage());
            return false;
        }

        long previousGeneration = gridGeneration;
        Instant previousReplacementAt = lastReplacementAt;
        activeRange = candidate;
        ladder = candidateLadder;
        sizing = candidateSizing;
        activeBudget = candidateBudget;
        gridGeneration++;
        lastReplacementAt = ctx.clock().instant();
        awaitingUpperReplacement = false;
        buyingStopped = shouldStopBuying();
        halted = false;
        try {
            persistState();
        } catch (Exception e) {
            activeRange = previous;
            ladder = previousLadder;
            sizing = previousSizing;
            activeBudget = previousBudget;
            gridGeneration = previousGeneration;
            lastReplacementAt = previousReplacementAt;
            awaitingUpperReplacement = true;
            failUpperReplacement("Не удалось сохранить новый диапазон: " + e.getMessage());
            return false;
        }

        updateSnapshot();
        String note = "GRID поколение %d: диапазон %s..%s заменён вверх на %s..%s"
                .formatted(gridGeneration,
                        previous.lower().toPlainString(), previous.upper().toPlainString(),
                        activeRange.lower().toPlainString(), activeRange.upper().toPlainString());
        try {
            ctx.ledgerMarker(LedgerEntryType.GRID_REPLACED, note);
        } catch (Exception e) {
            ctx.error("Диапазон заменён, но отметку GRID_REPLACED не удалось записать в книгу", e);
        }
        ctx.event(BotEventType.GRID_REPLACED, note);
        ensureOrders(null);
        return true;
    }

    private void failUpperReplacement(String reason) {
        halted = true;
        buyingStopped = true;
        updateSnapshot();
        ctx.event(BotEventType.RISK_BLOCKED,
                reason + ". Старая сетка сохранена, покупки остановлены.");
    }

    private void persistState() {
        ctx.saveState(new GridStrategyState(
                activeRange, gridGeneration, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingDownwardRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline));
    }

    /**
     * Выход цены за нижнюю границу — главный риск конструкции: без этой ветки
     * сетка неограниченно усредняется в падающий рынок.
     *
     * @return true, если бот дальше действовать не должен
     */
    private boolean checkRangeExit() {
        if (cfg.onRangeExit() == GridConfig.RangeExitAction.REPLACE_LOWER) {
            return false;
        }
        if (lastPrice.compareTo(activeRange.lower()) >= 0) {
            return false;
        }
        if (buyingStopped) {
            return cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP;
        }

        buyingStopped = true;
        updateSnapshot();

        if (cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP) {
            int cancelled = ctx.gateway().cancelAll(ctx.execution());
            ctx.event(BotEventType.RANGE_EXIT,
                    "Цена %s ушла ниже диапазона (%s). Снято заявок: %d, торговля остановлена."
                            .formatted(lastPrice.toPlainString(), activeRange.lower().toPlainString(), cancelled));
            return true;
        }

        ctx.event(BotEventType.RANGE_EXIT,
                "Цена %s ушла ниже диапазона (%s). Покупки прекращены, продажи продолжаются."
                        .formatted(lastPrice.toPlainString(), activeRange.lower().toPlainString()));
        return false;
    }

    // ==============================
    // HELPERS
    // ==============================

    private boolean isReady() {
        return ctx != null && ladder != null && cfg.enabled();
    }

    private FeeInfo resolveFeeInfo() {
        try {
            var fee = ctx.exchange().fees()
                    .getFeeInfo(ctx.execution().accountId(), ctx.execution().instrumentId());
            if (fee != null) {
                return fee;
            }
        } catch (Exception e) {
            log.warn("Не удалось получить ставку комиссии: {}", e.getMessage());
        }
        // Молча считать комиссию нулевой нельзя — это разрешило бы заведомо убыточную сетку.
        throw new IllegalStateException(
                "Не удалось определить ставку комиссии. Задайте её в настройках подключения.");
    }

    /**
     * Комиссия может меняться без рестарта бота: на криптобиржах тариф зависит от
     * объёма торгов, VIP-уровня и пары. Если текущий тариф сделал сетку убыточной,
     * новые покупки прекращаются; уже купленное продолжает продаваться.
     */
    private void refreshFeesIfNeeded() {
        if (nextFeeRefreshAt == null || ctx.clock().instant().isBefore(nextFeeRefreshAt)) {
            return;
        }
        nextFeeRefreshAt = ctx.clock().instant().plusSeconds(cfg.feeRefreshSeconds());

        FeeInfo refreshed;
        try {
            refreshed = resolveFeeInfo();
            // revalidate, а НЕ validate: размер заявки здесь пересчитывать нельзя.
            // Иначе у бота с реинвестированием прибыли объём менялся бы посреди жизни
            // сетки — между покупкой и её ещё не выставленной встречной продажей.
            GridValidator.revalidate(cfg, activeRange, ladder, ctx.constraints().minPriceIncrement(),
                    refreshed, ctx.execution().maxCapital(), sizing);
        } catch (Exception e) {
            if (!blockedByFees) {
                blockedByFees = true;
                buyingStopped = true;
                updateSnapshot();
                ctx.event(BotEventType.RISK_BLOCKED,
                        "Комиссии не удалось обновить или сетка перестала окупать оборот: "
                                + e.getMessage() + ". Новые покупки остановлены.");
            }
            return;
        }

        activeFees = refreshed;
        if (blockedByFees) {
            blockedByFees = false;
            buyingStopped = shouldStopBuying();
            updateSnapshot();
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Комиссия обновлена, экономика сетки снова проходит проверку. Покупки возобновлены.");
        }
    }

    /** Для UI: уровни сетки с их ценами. */
    public List<BigDecimal> ladderPrices() {
        return ladder == null ? List.of() : ladder.prices();
    }

    @Override
    public Optional<StrategySnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    private void updateSnapshot() {
        if (activeRange == null || ladder == null) {
            snapshot = null;
            return;
        }
        snapshot = new StrategySnapshot(
                activeRange.lower(), activeRange.upper(), ladder.prices(),
                activeRange.origin().name(), activeRange.since(), gridGeneration,
                buyingStopped, awaitingUpperReplacement || awaitingDownwardReplacement,
                awaitingDownwardReplacement ? "DOWN" : awaitingUpperReplacement ? "UP" : null,
                downwardReplacements, realizedDownwardLoss, halted,
                sizing == null ? null : sizing.lotsByLevel(),
                sizing == null ? null : sizing.mode().name(),
                sizing == null ? null : sizing.workingBudget(),
                sizing == null ? null : sizing.worstCaseNotional());
    }

    /** Человекочитаемое описание того, как посчитан размер заявки. */
    private String sizingSummary() {
        if (sizing == null) {
            return "размер заявки не рассчитан";
        }
        return switch (sizing.mode()) {
            case FIXED_LOTS -> "по %d лот(ов)".formatted(sizing.lotsAt(0));
            case UNIFORM -> "по %d лот(ов) (бюджет %s, задействовано %s)"
                    .formatted(sizing.lotsAt(0),
                            plain(sizing.workingBudget()), plain(sizing.worstCaseNotional()));
            case PER_LEVEL -> "по %d..%d лот(ов) по уровням (бюджет %s, задействовано %s, остаток %s)"
                    .formatted(sizing.minLots(), sizing.maxLots(),
                            plain(sizing.workingBudget()), plain(sizing.worstCaseNotional()),
                            plain(sizing.budgetLeftover()));
        };
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private record RangeResolution(
            GridRange range,
            long generation,
            BigDecimal referencePrice,
            boolean persist,
            GridStrategyState state
    ) {
    }
}
