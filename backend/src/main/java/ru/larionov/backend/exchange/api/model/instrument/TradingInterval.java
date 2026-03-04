package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.exchange.api.enums.TradingIntervalType;

import java.time.Instant;

public record TradingInterval(
        Instant startUtc,
        Instant endUtc,
        TradingIntervalType type
) {
}
