package ru.larionov.backend.exchange.api.model.account;

import java.math.BigDecimal;

public record MoneyBalance(
        String currency,          // тут это именно currency, потому что это баланс денег
        BigDecimal available,
        BigDecimal blocked
) {
}
