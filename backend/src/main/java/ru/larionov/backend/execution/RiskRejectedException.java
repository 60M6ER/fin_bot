package ru.larionov.backend.execution;

/** Ордер отклонён лимитами. Не ошибка выполнения — штатный отказ, о котором надо сообщить. */
public class RiskRejectedException extends RuntimeException {

    public RiskRejectedException(String message) {
        super(message);
    }
}
