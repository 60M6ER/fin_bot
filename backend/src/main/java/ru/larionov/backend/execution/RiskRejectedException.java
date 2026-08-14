package ru.larionov.backend.execution;

/** Ордер отклонён лимитами. Не ошибка выполнения — штатный отказ, о котором надо сообщить. */
public class RiskRejectedException extends RuntimeException {

    /**
     * Чем именно отказано.
     *
     * Нужна потому, что на разные отказы стратегия отвечает по-разному, а разбирать
     * текст сообщения строкой — значит привязать поведение к формулировке, которую
     * правят ради читаемости. Потолок короткой позиции стратегия умеет освободить,
     * переставив заявки ближе к цене; на остальные отказы ей ответить нечем.
     */
    public enum Reason {
        /** Потолок короткой позиции в штуках. */
        SHORT_QUANTITY_CEILING,
        /** Потолок короткой позиции в деньгах. */
        SHORT_NOTIONAL_CEILING,
        /** Всё прочее: у стратегии нет способа это обойти, и пытаться не надо. */
        OTHER
    }

    private final Reason reason;

    public RiskRejectedException(String message) {
        this(message, Reason.OTHER);
    }

    public RiskRejectedException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Отказ из-за потолка короткой позиции — единственный, который можно освободить. */
    public boolean isShortCeiling() {
        return reason == Reason.SHORT_QUANTITY_CEILING || reason == Reason.SHORT_NOTIONAL_CEILING;
    }
}
