package ru.larionov.backend.dto;

import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.model.RuntimeInfo;

import java.time.Instant;
import java.util.UUID;

public record BotDetailDto(
        UUID id,
        String name,
        StrategyType strategyType,
        String strategyConfig,
        boolean active,
        RuntimeInfo runtime,
        Instant createdAt,
        Instant updatedAt
) {}
