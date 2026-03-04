package ru.larionov.backend.exchange.api.model.order;

import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.TimeInForce;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;

public record OrderRequest(
        AccountId accountId,
        InstrumentId instrumentId,
        ClientOrderId clientOrderId, // обязательный
        OrderSide side,
        BigDecimal quantity,         // в "лотах/единицах" домена (см. ниже)
        BigDecimal limitPrice,       // число без валюты, валюту знаем из инструмента/котировки
        TimeInForce tif
) {
}
