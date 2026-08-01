package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;

import java.time.Instant;
import java.util.UUID;

public record ExchangeConnectionDetailDto(
        UUID id,
        ExchangeType exchange,
        String name,
        boolean active,
        RuntimeState runtimeState,
        String runtimeError,
        boolean sandboxEnabled,
        String apiKeyMasked,
        boolean hasSecret,
        boolean hasPassphrase,
        /** Секреты в БД зашифрованы (APP_SECRET_KEY задан). */
        boolean secretsEncrypted,
        String accountId,
        ExchangeConnectionSettings settings,
        Instant createdAt,
        Instant updatedAt
) {}
