package ru.larionov.backend.exchange.api.model.market;

import java.math.BigDecimal;

public record Price(
        BigDecimal value,
        String quoteCurrency
) {
}
