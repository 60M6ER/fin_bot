package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.util.List;
import java.util.Optional;

public interface OrdersApi {
    OrderResponse placeLimit(OrderRequest req);

    void cancel(AccountId accountId, OrderId orderId);

    Optional<OrderState> get(AccountId accountId, OrderId orderId);

    List<OrderState> listOpen(AccountId accountId, InstrumentId instrumentId);
}
