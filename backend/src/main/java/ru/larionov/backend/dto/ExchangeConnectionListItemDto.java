package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;

import java.util.UUID;

public record ExchangeConnectionListItemDto(
        UUID id,
        ExchangeType exchange,
        String name,
        boolean active
) {}
