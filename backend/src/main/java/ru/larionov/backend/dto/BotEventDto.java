package ru.larionov.backend.dto;

import ru.larionov.backend.entity.BotEventEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;

import java.time.Instant;
import java.util.UUID;

public record BotEventDto(
        UUID id,
        Instant ts,
        BotEventLevel level,
        BotEventType type,
        String message
) {
    public static BotEventDto of(BotEventEntity e) {
        return new BotEventDto(e.getId(), e.getTs(), e.getLevel(), e.getType(), e.getMessage());
    }
}
