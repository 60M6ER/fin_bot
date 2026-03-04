package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.account.AccountInfo;
import ru.larionov.backend.exchange.api.model.account.AccountState;
import ru.larionov.backend.exchange.api.model.account.Position;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.util.List;
import java.util.Optional;

public interface AccountsApi {
    List<AccountInfo> listAccounts();

    AccountState getState(AccountId accountId);

    Optional<Position> getPosition(AccountId accountId, InstrumentId instrumentId);
}
