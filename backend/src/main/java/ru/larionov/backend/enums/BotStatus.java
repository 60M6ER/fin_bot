package ru.larionov.backend.enums;

public enum BotStatus {

    /** Бот создан, но не запускался */
    DRAFT,

    /** Бот активен и торгует */
    RUNNING,

    /** Бот остановлен пользователем */
    STOPPED,

    /** Ошибка (API, стратегия, данные и т.п.) */
    ERROR
}

