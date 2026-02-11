package ru.larionov.backend.dto;

import ru.larionov.backend.enums.BotStatus;
import ru.larionov.backend.enums.StrategyType;

import java.util.UUID;

public record BotCreateRequest(
        String name,
        UUID exchangeConnectionId,
        StrategyType strategyType,
        String strategyConfig,
        String symbol,
        String timeframe,
        BotStatus status,
        Boolean enabled
) {}
