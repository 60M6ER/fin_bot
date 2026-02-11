package ru.larionov.backend.dto;

import ru.larionov.backend.enums.BotStatus;
import ru.larionov.backend.enums.StrategyType;

import java.time.Instant;
import java.util.UUID;

public record BotDetailDto(
        UUID id,
        String name,
        UUID exchangeConnectionId,
        String exchangeConnectionName,
        StrategyType strategyType,
        String strategyConfig,
        String symbol,
        String timeframe,
        BotStatus status,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {}
