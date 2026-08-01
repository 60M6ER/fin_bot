package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
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

    /** Торгуются ли сейчас лимитные заявки. До первого события статуса — неизвестно. */
    private boolean limitOrdersAvailable;

    /** Выход из диапазона вниз: покупки прекращены. */
    private boolean buyingStopped;

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

        BigDecimal commission = resolveCommissionRate();

        // Отказ стартовать — осознанное решение: сетка, не окупающая комиссию,
        // будет исправно терять деньги на каждом обороте.
        GridValidator.validate(cfg, ladder, constraints.minPriceIncrement(),
                commission, constraints.lot(), ctx.execution().maxCapital());

        ctx.event(BotEventType.HOUSEKEEPING,
                "GRID готов: %d уровней от %s до %s, шаг %s, по %d лот(ов), комиссия %s%%"
                        .formatted(cfg.levels(),
                                cfg.lowerPrice().toPlainString(),
                                cfg.upperPrice().toPlainString(),
                                ladder.effectiveStep().toPlainString(),
                                cfg.lotsPerOrder(),
                                commission.multiply(BigDecimal.valueOf(100)).toPlainString()));
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
            ReconcileResult reconciled = ctx.gateway().reconcile(ctx.execution());
            ensureOrders(reconciled);
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
        int sellLevel = filledBuy.gridLevel() + 1;
        BigDecimal price = ladder.priceAt(sellLevel);

        if (price == null) {
            // Купили на верхнем уровне — продавать выше некуда.
            ctx.warn("Покупка на верхнем уровне сетки (%d): встречной продажи нет"
                    .formatted(filledBuy.gridLevel()));
            return;
        }

        boolean alreadyThere = ctx.gateway().openOrders(ctx.botId()).stream()
                .anyMatch(o -> o.side() == OrderSide.SELL
                        && o.gridLevel() != null && o.gridLevel() == sellLevel);
        if (alreadyThere) {
            return;
        }

        place(OrderSide.SELL, price, sellLevel, new HashMap<>());
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
        ReconcileResult reconciled = ctx.gateway().reconcile(ctx.execution());
        ensureOrders(reconciled);
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

        List<BotOrderView> open = ctx.gateway().openOrders(ctx.botId());
        Map<Integer, BotOrderView> openBuys = new HashMap<>();
        Map<Integer, BotOrderView> openSells = new HashMap<>();

        for (BotOrderView o : open) {
            if (o.gridLevel() == null) {
                continue;
            }
            if (o.side() == OrderSide.BUY) {
                openBuys.put(o.gridLevel(), o);
            } else {
                openSells.put(o.gridLevel(), o);
            }
        }

        // Непроданный инвентарь ищем только когда есть свежая сверка: без неё
        // достоверной позиции нет, а гадать здесь — значит рисковать лишней продажей.
        if (reconciled != null) {
            placeMissingSells(openSells, reconciled.positionLots().longValue());
        }
        if (!buyingStopped) {
            placeMissingBuys(openBuys, openSells);
        }
    }

    /**
     * Купленное, но не выставленное на продажу. Возникает после исполнения покупки
     * и после рестарта; отдельная ветка нужна ещё и потому, что постановку продажи
     * мог отклонить риск-лимит — тогда инвентарь висит непроданным.
     */
    private void placeMissingSells(Map<Integer, BotOrderView> openSells, long position) {
        long covered = openSells.values().stream().mapToLong(BotOrderView::remainingLots).sum();
        long uncovered = position - covered;

        while (uncovered >= cfg.lotsPerOrder()) {
            int level = nextFreeSellLevel(openSells);
            if (level < 0) {
                // Все уровни выше цены заняты — продавать некуда, ждём движения.
                return;
            }
            BigDecimal price = ladder.priceAt(level);
            if (!place(OrderSide.SELL, price, level, openSells)) {
                return;
            }
            uncovered -= cfg.lotsPerOrder();
        }
    }

    /** Покупки на свободных уровнях ниже цены, ближайшие к рынку — первыми. */
    private void placeMissingBuys(Map<Integer, BotOrderView> openBuys, Map<Integer, BotOrderView> openSells) {
        int activeCount = openBuys.size() + openSells.size();
        int startLevel = ladder.highestLevelBelow(lastPrice);

        for (int level = startLevel; level >= 0; level--) {
            if (activeCount >= cfg.maxActiveOrders()) {
                return;
            }
            if (openBuys.containsKey(level)) {
                continue;
            }
            // Уровень занят, если на следующем висит наша продажа: это купленный инвентарь.
            if (openSells.containsKey(level + 1)) {
                continue;
            }
            BigDecimal price = ladder.priceAt(level);
            if (price == null || price.compareTo(cfg.lowerPrice()) < 0) {
                continue;
            }
            if (!place(OrderSide.BUY, price, level, openBuys)) {
                return;
            }
            activeCount++;
        }
    }

    /**
     * @return false, если ставить дальше нет смысла (упёрлись в лимит)
     */
    private boolean place(OrderSide side, BigDecimal price, int level, Map<Integer, BotOrderView> registry) {
        try {
            BotOrderView placed = ctx.gateway()
                    .placeLimit(ctx.execution(), new PlaceIntent(side, cfg.lotsPerOrder(), price, level));
            registry.put(level, placed);
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

    private int nextFreeSellLevel(Map<Integer, BotOrderView> openSells) {
        int start = ladder.lowestLevelAbove(lastPrice);
        if (start < 0) {
            // Цена выше всей сетки: продаём по верхней границе.
            start = ladder.levelCount();
        }
        for (int level = start; level <= ladder.levelCount(); level++) {
            if (!openSells.containsKey(level)) {
                return level;
            }
        }
        return -1;
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

    private BigDecimal resolveCommissionRate() {
        try {
            var fee = ctx.exchange().fees()
                    .getFeeInfo(ctx.execution().accountId(), ctx.execution().instrumentId());
            if (fee != null && fee.makerRate() != null) {
                return fee.makerRate();
            }
        } catch (Exception e) {
            log.warn("Не удалось получить ставку комиссии: {}", e.getMessage());
        }
        // Молча считать комиссию нулевой нельзя — это разрешило бы заведомо убыточную сетку.
        throw new IllegalStateException(
                "Не удалось определить ставку комиссии. Задайте её в настройках подключения.");
    }

    /** Для UI: уровни сетки с их ценами. */
    public List<BigDecimal> ladderPrices() {
        return ladder == null ? List.of() : ladder.prices();
    }
}
