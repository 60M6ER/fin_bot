package ru.larionov.backend.dto;

import ru.larionov.backend.enums.StrategyType;

import java.util.UUID;

/**
 * Обновление бота. Применяется только к остановленному боту:
 * менять стратегию или подключение под работающим ботом нельзя —
 * у него уже выставлены ордера, привязанные к текущей конфигурации.
 */
public record BotUpdateRequest(
        String name,
        StrategyType strategyType,
        UUID exchangeConnectionId,
        String strategyConfig
) {}
