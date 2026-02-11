package ru.larionov.backend.dto;

import ru.larionov.backend.enums.BotStatus;
import ru.larionov.backend.enums.StrategyType;

import java.util.UUID;

public record BotListItemDto(
        UUID id,
        String name,
        StrategyType strategyType,
        String symbol,
        BotStatus status,
        boolean enabled,
        UUID exchangeConnectionId,
        String exchangeConnectionName
) {}