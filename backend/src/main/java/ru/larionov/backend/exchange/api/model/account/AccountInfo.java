package ru.larionov.backend.exchange.api.model.account;

import ru.larionov.backend.exchange.api.enums.AccountType;
import ru.larionov.backend.exchange.api.model.id.AccountId;

public record AccountInfo(
        AccountId id,
        String name,
        AccountType type,
        boolean sandbox
) {
}
