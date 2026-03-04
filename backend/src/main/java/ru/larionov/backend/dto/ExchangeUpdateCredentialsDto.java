package ru.larionov.backend.dto;

import java.util.UUID;

public record ExchangeUpdateCredentialsDto(
        UUID id,
        String apiKey,
        String apiSecret,
        String passphrase
) {
}
