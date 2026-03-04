package ru.larionov.backend.exchange.api.model.market;

import ru.larionov.backend.exchange.api.enums.CandleInterval;

import java.time.Instant;

public record CandlesQuery(
        Instant from,
        Instant to,
        CandleInterval interval
) {
}
