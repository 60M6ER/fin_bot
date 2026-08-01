package ru.larionov.backend.exchange.api.enums;

public enum OrderStatus {

    /**
     * Наше внутреннее состояние: запись создана до сетевого вызова, исход неизвестен.
     * Появляется, когда постановка ордера не успела ответить (таймаут, обрыв).
     * Разрешается сверкой — запросом состояния по нашему clientOrderId.
     */
    PENDING,

    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
    EXPIRED,
    UNKNOWN;

    /** Терминальные статусы: ордер больше не живёт на бирже и не изменится. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELLED || this == REJECTED || this == EXPIRED;
    }

    /** Ордер может быть жив на бирже — учитываем при сверке и в лимитах. */
    public boolean isOpen() {
        return !isTerminal();
    }

    /** Ордер точно стоит в стакане — в отличие от PENDING/UNKNOWN, где мы не уверены. */
    public boolean isActive() {
        return this == NEW || this == PARTIALLY_FILLED;
    }
}
