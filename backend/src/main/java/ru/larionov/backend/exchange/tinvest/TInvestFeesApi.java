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

    /**
     * Ставка берётся из настроек подключения (тариф пользователя), а не хардкодится:
     * от неё зависит проверка безубытка шага сетки, и ошибка здесь означает
     * торговлю, которая не окупает издержки.
     *
     * API тарифов T-Invest не отдаёт ставку дёшево, поэтому её задаёт пользователь.
     * maker и taker у брокера совпадают, в отличие от криптобирж.
     */
    @Override
    public FeeInfo getFeeInfo(AccountId accountId, InstrumentId instrumentId) {
        BigDecimal rate = client.commissionRate();
        return new FeeInfo(rate, rate);
    }
}
