package ru.larionov.backend.enums;

/**
 * Тип события бота. От него зависит маршрутизация: часть уходит в Telegram,
 * часть остаётся только в консоли и журнале.
 *
 * Правило простое: в Telegram идёт то, что меняет деньги или требует внимания.
 * Котировки и housekeeping — нет, иначе телефон превратится в пулемёт.
 */
public enum BotEventType {

    BOT_STARTED(true),
    BOT_STOPPED(true),

    ORDER_PLACED(true),
    ORDER_FILLED(true),
    ORDER_CANCELLED(true),
    ORDER_REJECTED(true),

    /**
     * Закрыт цикл сетки: покупка уровня сопоставлена с продажей.
     *
     * Уходит в Telegram: это единственное событие, в котором сетка фактически
     * зарабатывает, и результат цикла — ровно то, ради чего за ней следят.
     */
    CYCLE_CLOSED(true),

    /** Цена вышла за границы сетки — главный риск конструкции. */
    RANGE_EXIT(true),

    /** Диапазон сетки заменён после подтверждённого выхода цены. */
    GRID_REPLACED(true),

    /** Ордер не выставлен из-за лимита: капитал, позиция, частота, стоп-кран. */
    RISK_BLOCKED(true),

    STREAM_RECONNECTED(true),

    /** Сверка с биржей: сколько нашлось расхождений. */
    RECONCILED(false),

    ERROR(true),

    /** Служебное: тики, отсутствие изменений. Только консоль и журнал. */
    HOUSEKEEPING(false);

    private final boolean notifiable;

    BotEventType(boolean notifiable) {
        this.notifiable = notifiable;
    }

    public boolean isNotifiable() {
        return notifiable;
    }
}
