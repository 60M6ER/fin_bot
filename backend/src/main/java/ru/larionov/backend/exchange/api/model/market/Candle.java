package ru.larionov.backend.exchange.api.model.market;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.time.Instant;

public record Candle(
        InstrumentId instrumentId,
        Price open,
        Price high,
        Price low,
        Price close,
        BigDecimal volume,
        Instant startTs,
        Instant endTs
) {
}
