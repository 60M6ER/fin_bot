package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;

import java.time.Instant;
import java.util.UUID;

public record ExchangeConnectionDetailDto(
        UUID id,
        ExchangeType exchange,
        String name,
        boolean active,
        boolean sandboxEnabled,
        String apiKeyMasked,
        boolean hasSecret,
        boolean hasPassphrase,
        Instant createdAt,
        Instant updatedAt
) {}
