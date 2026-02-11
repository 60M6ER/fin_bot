package ru.larionov.backend.enums;

public enum StrategyType {
    /** Grid trading */
    GRID,

    /** Заглушка / тестовая стратегия */
    NONE,

    /** Простая стратегия: покупка + фиксация профита */
    SIMPLE_PROFIT,

    /** Следование за трендом */
    TREND_FOLLOWING,

    /** Средняя цена / усреднение позиции */
    DCA,

    /** Арбитраж (внутри одной биржи или между) */
    ARBITRAGE
}
