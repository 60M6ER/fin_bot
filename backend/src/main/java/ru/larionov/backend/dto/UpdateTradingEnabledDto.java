package ru.larionov.backend.dto;

/** Глобальный стоп-кран: при false ни один бот не имеет права выставить ордер. */
public record UpdateTradingEnabledDto(boolean enabled) {}
