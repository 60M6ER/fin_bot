package ru.larionov.backend.dto;

import ru.larionov.backend.enums.StrategyType;

import java.util.UUID;

public record BotCreateRequest(
        String name,
        StrategyType strategyType,
        UUID exchangeConnectionId,
        String strategyConfig
) {}
