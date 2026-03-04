package ru.larionov.backend.exchange.api.model.instrument;

import java.math.BigDecimal;

public record TradingConstraints(
        int lot,
        BigDecimal minPriceIncrement,
        String quoteCurrency
) {
}
