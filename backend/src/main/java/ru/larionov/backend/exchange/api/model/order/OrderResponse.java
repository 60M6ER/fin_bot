package ru.larionov.backend.exchange.api.model.order;

import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.OrderId;

public record OrderResponse(
        OrderId orderId,
        ClientOrderId clientOrderId,
        OrderState state
) {}
