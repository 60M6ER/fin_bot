package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;

public interface FeesApi {
    FeeInfo getFeeInfo(AccountId accountId, InstrumentId instrumentId);
}
