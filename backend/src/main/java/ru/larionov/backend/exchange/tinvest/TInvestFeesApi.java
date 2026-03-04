package ru.larionov.backend.exchange.tinvest;

import java.math.BigDecimal;

import ru.larionov.backend.exchange.api.FeesApi;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.FeeInfo;

public class TInvestFeesApi implements FeesApi {

    private final TInvestExchangeClient client;

    public TInvestFeesApi(TInvestExchangeClient client) {
        this.client = client;
    }

    // Временно хардкодим комиссию 0.05% (0.0005)
    // Позже можно заменить на реальный вызов API тарифов
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("0.0005");

    @Override
    public FeeInfo getFeeInfo(AccountId accountId, InstrumentId instrumentId) {
        // Пока не учитываем тип счета, инструмент или тариф
        // Для MVP возвращаем одинаковую комиссию maker/taker
        return new FeeInfo(
                DEFAULT_RATE,
                DEFAULT_RATE
        );
    }
}
