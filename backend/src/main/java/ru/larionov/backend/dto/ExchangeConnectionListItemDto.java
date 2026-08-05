package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.RuntimeState;

import java.util.UUID;

public record ExchangeConnectionListItemDto(
        UUID id,
        ExchangeType exchange,
        String name,
        boolean active,         // desired/факт по твоей семантике (B)
        RuntimeState runtimeState,
        String runtimeError,     // nullable
        /** Никогда не null: шаблоны читают поля без проверок. */
        ConnectionValuationDto valuation
) {}
