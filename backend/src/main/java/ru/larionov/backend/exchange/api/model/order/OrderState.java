package ru.larionov.backend.exchange.api.model.order;

import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderState(
        OrderId orderId,
        ClientOrderId clientOrderId,
        AccountId accountId,
        InstrumentId instrumentId,
        OrderSide side,

        BigDecimal requestedQuantity,
        BigDecimal executedQuantity,

        BigDecimal limitPrice,
        BigDecimal averageExecutedPrice,
        OrderFee fee,

        OrderStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
