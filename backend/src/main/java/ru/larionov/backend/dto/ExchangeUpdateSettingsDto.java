package ru.larionov.backend.dto;

import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;

import java.util.UUID;

public record ExchangeUpdateSettingsDto(
        UUID id,
        String accountId,
        ExchangeConnectionSettings settings
) {}
