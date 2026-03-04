package ru.larionov.backend.exchange.api.model.instrument;

import java.math.BigDecimal;

public record InstrumentDetails(
        InstrumentBrief brief,
        int lot,
        BigDecimal minPriceIncrement,
        boolean buyAvailable,
        boolean sellAvailable,
        boolean apiTradeAvailable
) {
}
