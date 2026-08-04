package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.execution.RiskRejectedException;
import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Последняя известная цена: нужна, чтобы решать, где покупать, а где продавать. */
    private BigDecimal lastPrice;

    /**
     * Торгуются ли сейчас лимитные заявки.
     *
     * Источник правды — стрим статуса торгов, но при старте бот дополнительно
     * спрашивает REST-календарь (см. {@link #seedTradingStatusFromCalendar()}):
     * неизвестно наверняка, шлёт ли стрим снимок текущего статуса сразу при
     * подписке или только при СМЕНЕ статуса. Если только при смене — бот,
     * запущенный посреди открытой сессии, просидел бы без единого ордера
     * до следующего перехода статуса, то есть часами.
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
        this.ctx = ctx;

        if (!cfg.enabled()) {
            ctx.warn("GRID: стратегия выключена в конфигурации (enabled=false)");
            return;
        }

        TradingConstraints constraints = ctx.constraints();
        this.ladder = GridLadder.build(cfg, constraints.minPriceIncrement());

        FeeInfo fees = resolveFeeInfo();
        activeFees = fees;
        nextFeeRefreshAt = ctx.clock().instant().plusSeconds(cfg.feeRefreshSeconds());

        // Отказ стартовать — осознанное решение: сетка, не окупающая комиссию,
        // будет исправно терять деньги на каждом обороте.
        GridValidator.validate(cfg, ladder, constraints.minPriceIncrement(),
                fees, constraints.lot(), ctx.execution().maxCapital());

        ctx.event(BotEventType.HOUSEKEEPING,
                "GRID готов: %d уровней от %s до %s, шаг %s, по %d лот(ов), комиссия %s%%"
                        .formatted(cfg.levels(),
                                cfg.lowerPrice().toPlainString(),
                                cfg.upperPrice().toPlainString(),
                                ladder.effectiveStep().toPlainString(),
                                cfg.lotsPerOrder(),
                                fees.makerRoundTripRate().multiply(BigDecimal.valueOf(100)).toPlainString()));

        seedTradingStatusFromCalendar();
    }

    /**
     * Подстраховка на случай запуска посреди открытой сессии.
     *
     * Не блокирует старт при ошибке: если календарь недоступен, статус всё равно
     * подтвердится стримом, когда/если тот пришлёт снимок или смену.
     */
    private void seedTradingStatusFromCalendar() {
        try {
            var state = ctx.exchange().calendar().getState(ctx.clock().instant());
            if (state != null && state.tradableNow()) {
                limitOrdersAvailable = true;
                ctx.event(BotEventType.HOUSEKEEPING,
                        "Календарь сообщает: рынок сейчас открыт — сетка расставится по первой цене, "
                                + "не дожидаясь события стрима");
            }
        } catch (Exception e) {
            log.warn("Не удалось получить статус торгов из календаря при старте: {}", e.getMessage());
        }
    }

    @Override
    public void onStop() {
        this.ladder = null;
        this.ctx = null;
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
            ensureOrders(reconcileAndCheck());
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
        if (!isReady() || order == null || order.gridLevel() == null) {
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
            ensureOrders(null);
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
        if (held - covered < cfg.lotsPerOrder()) {
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
        ensureOrders(null);
    }

    @Override
    public void onTick() {
        if (!isReady()) {
            return;
        }
        // Сторож: периодическая сверка на случай, если стрим молчит не потому,
        // что рынок спокоен, а потому что мы ослепли.
        ensureOrders(reconcileAndCheck());
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
        if (blockedByFees) {
            return;
        }

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
            if (heldLots - covered < cfg.lotsPerOrder()) {
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
            openSellLotsByLevel.merge(buyLevel, (long) cfg.lotsPerOrder(), Long::sum);
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
            if (price == null || price.compareTo(cfg.lowerPrice()) < 0) {
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
        try {
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(side, cfg.lotsPerOrder(), price, level));
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
     * Выход цены за нижнюю границу — главный риск конструкции: без этой ветки
     * сетка неограниченно усредняется в падающий рынок.
     *
     * @return true, если бот дальше действовать не должен
     */
    private boolean checkRangeExit() {
        if (lastPrice.compareTo(cfg.lowerPrice()) >= 0) {
            return false;
        }
        if (buyingStopped) {
            return cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP;
        }

        buyingStopped = true;

        if (cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP) {
            int cancelled = ctx.gateway().cancelAll(ctx.execution());
            ctx.event(BotEventType.RANGE_EXIT,
                    "Цена %s ушла ниже диапазона (%s). Снято заявок: %d, торговля остановлена."
                            .formatted(lastPrice.toPlainString(), cfg.lowerPrice().toPlainString(), cancelled));
            return true;
        }

        ctx.event(BotEventType.RANGE_EXIT,
                "Цена %s ушла ниже диапазона (%s). Покупки прекращены, продажи продолжаются."
                        .formatted(lastPrice.toPlainString(), cfg.lowerPrice().toPlainString()));
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
            GridValidator.validate(cfg, ladder, ctx.constraints().minPriceIncrement(),
                    refreshed, ctx.constraints().lot(), ctx.execution().maxCapital());
        } catch (Exception e) {
            if (!blockedByFees) {
                blockedByFees = true;
                buyingStopped = true;
                ctx.event(BotEventType.RISK_BLOCKED,
                        "Комиссии не удалось обновить или сетка перестала окупать оборот: "
                                + e.getMessage() + ". Новые покупки остановлены.");
            }
            return;
        }

        activeFees = refreshed;
        if (blockedByFees) {
            blockedByFees = false;
            buyingStopped = false;
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Комиссия обновлена, экономика сетки снова проходит проверку. Покупки возобновлены.");
        }
    }

    /** Для UI: уровни сетки с их ценами. */
    public List<BigDecimal> ladderPrices() {
        return ladder == null ? List.of() : ladder.prices();
    }
}
