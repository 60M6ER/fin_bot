package ru.larionov.backend.exchange.api.model;

import java.math.BigDecimal;

public record FeeInfo(
        BigDecimal makerRate,
        BigDecimal takerRate
) {
}
