package ru.larionov.backend.exchange.api.model.account;

import ru.larionov.backend.exchange.api.model.id.AccountId;

import java.time.Instant;
import java.util.List;

public record AccountState(
        AccountId accountId,
        List<MoneyBalance> balances,
        List<Position> positions,
        Instant ts
) {
}
