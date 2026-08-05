package ru.larionov.backend.dto;

import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.model.RuntimeInfo;

import java.time.Instant;
import java.util.UUID;

public record BotDetailDto(
        UUID id,
        String name,
        StrategyType strategyType,
        UUID exchangeConnectionId,
        String exchangeConnectionName,
        /** Runtime-статус подключения: без него в UI непонятно, почему бот не поднялся. */
        RuntimeState exchangeConnectionState,
        String strategyConfig,
        boolean active,
        RuntimeInfo runtime,
        Instant createdAt,
        Instant updatedAt
) {}
