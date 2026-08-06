package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Заглушка биржи, воспроизводящая поведение, ради которого построена идемпотентность:
 * биржа МОЖЕТ принять ордер и не ответить.
 */
public class FakeOrdersApi implements OrdersApi {

    /** Ордера, «принятые» биржей, по нашему clientOrderId. */
    public final Map<String, OrderState> accepted = new LinkedHashMap<>();

    /** Все попытки постановки, включая те, на которые мы не ответили. */
    public final List<String> placeAttempts = new ArrayList<>();

    /** Если true — принимаем ордер, но бросаем исключение вместо ответа. */
    public boolean acceptThenTimeout = false;

    /** Если true — постановка падает, не приняв ордер. */
    public boolean rejectOutright = false;

    /**
     * Если true — запрос состояния по clientOrderId падает.
     * Так выглядит разомкнутый circuit breaker или недоступный метод: судьбу записи
     * выяснить нельзя, при том что список живых заявок биржа отдаёт исправно.
     */
    public boolean stateLookupFails = false;

    @Override
    public OrderResponse placeLimit(OrderRequest req) {
        String clientOrderId = req.clientOrderId().value();
        placeAttempts.add(clientOrderId);

        if (rejectOutright) {
            throw new RuntimeException("сеть недоступна");
        }

        OrderState state = state(req, OrderStatus.NEW, 0, null);
        accepted.put(clientOrderId, state);

        if (acceptThenTimeout) {
            // Именно этот сценарий и опасен: биржа ордер приняла, а мы об этом не знаем.
            throw new RuntimeException("таймаут ответа");
        }
        return new OrderResponse(state.orderId(), req.clientOrderId(), state);
    }

    private OrderState state(OrderRequest req, OrderStatus status, long executed, OrderFee fee) {
        return new OrderState(
                new OrderId("exch-" + req.clientOrderId().value()),
                req.clientOrderId(),
                req.accountId(),
                req.instrumentId(),
                req.side(),
                req.quantity(),
                BigDecimal.valueOf(executed),
                req.limitPrice(),
                req.limitPrice(),
                fee,
                status,
                Instant.now(),
                Instant.now());
    }

    /** Имитирует исполнение уже принятого ордера. */
    public void fill(String clientOrderId) {
        fill(clientOrderId, null);
    }

    public void fill(String clientOrderId, OrderFee fee) {
        OrderState s = accepted.get(clientOrderId);
        if (s == null) {
            return;
        }
        accepted.put(clientOrderId, new OrderState(
                s.orderId(), s.clientOrderId(), s.accountId(), s.instrumentId(), s.side(),
                s.requestedQuantity(), s.requestedQuantity(),
                s.limitPrice(), s.limitPrice(), fee,
                OrderStatus.FILLED, s.createdAt(), Instant.now()));
    }

    /** Имитирует частичное исполнение уже принятого ордера. */
    public void partialFill(String clientOrderId, long executed, OrderFee fee) {
        OrderState s = accepted.get(clientOrderId);
        if (s == null) {
            return;
        }
        accepted.put(clientOrderId, new OrderState(
                s.orderId(), s.clientOrderId(), s.accountId(), s.instrumentId(), s.side(),
                s.requestedQuantity(), BigDecimal.valueOf(executed),
                s.limitPrice(), s.limitPrice(), fee,
                OrderStatus.PARTIALLY_FILLED, s.createdAt(), Instant.now()));
    }

    @Override
    public void cancel(AccountId accountId, OrderId orderId) {
        accepted.entrySet().removeIf(e -> e.getValue().orderId().value().equals(orderId.value()));
    }

    @Override
    public Optional<OrderState> get(AccountId accountId, OrderId orderId) {
        return accepted.values().stream()
                .filter(s -> s.orderId().value().equals(orderId.value()))
                .findFirst();
    }

    @Override
    public Optional<OrderState> getByClientOrderId(AccountId accountId, ClientOrderId clientOrderId) {
        if (stateLookupFails) {
            throw new RuntimeException("CircuitBreaker 'OrdersService/GetOrderState' is OPEN");
        }
        return Optional.ofNullable(accepted.get(clientOrderId.value()));
    }

    @Override
    public List<OrderState> listOpen(AccountId accountId, InstrumentId instrumentId) {
        return accepted.values().stream()
                .filter(s -> s.status() == OrderStatus.NEW || s.status() == OrderStatus.PARTIALLY_FILLED)
                .toList();
    }
}
