package ru.larionov.backend.exchange.api.model.market;

import java.math.BigDecimal;

public record OrderBookLevel(
        Price price,
        BigDecimal quantity
) {
}
