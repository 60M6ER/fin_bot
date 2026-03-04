package ru.larionov.backend.dto;

import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.model.RuntimeInfo;

import java.util.UUID;

public record BotListItemDto(
        UUID id,
        String name,
        StrategyType strategyType,
        boolean active,
        RuntimeInfo runtime
) {}