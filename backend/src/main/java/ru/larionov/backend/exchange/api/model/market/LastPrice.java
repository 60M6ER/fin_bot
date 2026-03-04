package ru.larionov.backend.exchange.api.model.market;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.time.Instant;

public record LastPrice(
        InstrumentId instrumentId,
        Price price,
        Instant ts
) {
}
