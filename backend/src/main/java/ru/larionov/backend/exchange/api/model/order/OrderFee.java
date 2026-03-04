package ru.larionov.backend.exchange.api.model.order;

import java.math.BigDecimal;

public record OrderFee(
        BigDecimal rate,          // например 0.003 или 0.0005
        BigDecimal estimated,     // оценка в quoteCurrency
        BigDecimal actual         // факт в quoteCurrency (может быть null до исполнения)
) {
}
