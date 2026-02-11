package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;

public record ExchangeConnectionCreateRequest(
        ExchangeType exchange,
        String name,
        String apiKey,
        String apiSecret,
        String passphrase
) {}
