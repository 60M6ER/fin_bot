package ru.larionov.backend.exchange.tinvest;

import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.enums.TimeInForce;
import ru.larionov.backend.exchange.api.model.order.CommissionSource;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * T-Invest implementation of OrdersApi (limit orders only for now).
 */
@Slf4j
public class TInvestOrdersApi implements OrdersApi {

    private final TInvestExchangeClient client;

    public TInvestOrdersApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public OrderResponse placeLimit(OrderRequest req) {
        Objects.requireNonNull(req, "req");

        if (req.accountId() == null || req.accountId().value() == null || req.accountId().value().isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (req.instrumentId() == null || req.instrumentId().uid() == null || req.instrumentId().uid().isBlank()) {
            throw new IllegalArgumentException("instrumentId.uid is required for T-Invest orders");
        }
        if (req.clientOrderId() == null || req.clientOrderId().value() == null || req.clientOrderId().value().isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        if (req.quantity() == null) {
            throw new IllegalArgumentException("quantity is required");
        }
        if (req.limitPrice() == null) {
            throw new IllegalArgumentException("limitPrice is required");
        }

        long lots = req.quantity().setScale(0, RoundingMode.DOWN).longValueExact();
        if (lots <= 0) {
            throw new IllegalArgumentException("quantity (lots) must be > 0");
        }

        PostOrderRequest request = PostOrderRequest.newBuilder()
                .setAccountId(req.accountId().value())
                .setInstrumentId(req.instrumentId().uid())
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setDirection(mapSide(req.side()))
                .setQuantity(lots)
                .setPrice(toQuotation(req.limitPrice()))
                .setOrderId(req.clientOrderId().value()) // client order id
                .setTimeInForce(mapTif(req.tif()))
                .build();

        PostOrderResponse response = client.ordersStub()
                .callSyncMethod(
                        OrdersServiceGrpc.getPostOrderMethod(),
                        stub -> stub.postOrder(request)
                );

        OrderId orderId = new OrderId(response.getOrderId());
        ClientOrderId clientOrderId = req.clientOrderId();

        ru.larionov.backend.exchange.api.model.order.OrderState state = mapPostOrderState(req, orderId, clientOrderId, response);

        return new OrderResponse(orderId, clientOrderId, state);
    }

    @Override
    public void cancel(AccountId accountId, OrderId orderId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(orderId, "orderId");

        CancelOrderRequest request = CancelOrderRequest.newBuilder()
                .setAccountId(accountId.value())
                .setOrderId(orderId.value())
                .build();

        client.ordersStub()
                .callSyncMethod(
                        OrdersServiceGrpc.getCancelOrderMethod(),
                        stub -> stub.cancelOrder(request)
                );
    }

    @Override
    public Optional<ru.larionov.backend.exchange.api.model.order.OrderState> get(AccountId accountId, OrderId orderId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(orderId, "orderId");

        GetOrderStateRequest request = GetOrderStateRequest.newBuilder()
                .setAccountId(accountId.value())
                .setOrderId(orderId.value())
                .build();

        try {
            ru.tinkoff.piapi.contract.v1.OrderState s = client.ordersStub()
                    .callSyncMethod(
                            OrdersServiceGrpc.getGetOrderStateMethod(),
                            stub -> stub.getOrderState(request)
                    );
            return Optional.of(mapOrderState(accountId, s));
        } catch (RuntimeException e) {
            // Ловим RuntimeException, а не StatusRuntimeException: SDK оборачивает
            // исходное исключение в ServiceRuntimeException, см. TInvestErrors.
            if (TInvestErrors.isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Состояние по нашему clientOrderId — через OrderIdType.ORDER_ID_TYPE_REQUEST.
     * Именно это позволяет после оборванной постановки узнать судьбу ордера,
     * не имея биржевого идентификатора, и не выставить дубль.
     */
    @Override
    public Optional<ru.larionov.backend.exchange.api.model.order.OrderState> getByClientOrderId(
            AccountId accountId, ClientOrderId clientOrderId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(clientOrderId, "clientOrderId");

        GetOrderStateRequest request = GetOrderStateRequest.newBuilder()
                .setAccountId(accountId.value())
                .setOrderId(clientOrderId.value())
                .setOrderIdType(OrderIdType.ORDER_ID_TYPE_REQUEST)
                .build();

        try {
            ru.tinkoff.piapi.contract.v1.OrderState s = client.ordersStub()
                    .callSyncMethod(
                            OrdersServiceGrpc.getGetOrderStateMethod(),
                            stub -> stub.getOrderState(request)
                    );
            return Optional.of(mapOrderState(accountId, s));
        } catch (RuntimeException e) {
            if (TInvestErrors.isNotFound(e)) {
                // Ордера с таким clientOrderId у биржи нет — значит его не приняли.
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<ru.larionov.backend.exchange.api.model.order.OrderState> listOpen(AccountId accountId, InstrumentId instrumentId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(instrumentId, "instrumentId");

        GetOrdersRequest request = GetOrdersRequest.newBuilder()
                .setAccountId(accountId.value())
                .build();

        GetOrdersResponse response = client.ordersStub()
                .callSyncMethod(
                        OrdersServiceGrpc.getGetOrdersMethod(),
                        stub -> stub.getOrders(request)
                );

        String uid = instrumentId.uid();
        List<ru.larionov.backend.exchange.api.model.order.OrderState> result = new ArrayList<>();
        for (ru.tinkoff.piapi.contract.v1.OrderState s : response.getOrdersList()) {
            if (uid != null && !uid.isBlank()) {
                // In this response instrument id is provided as instrument_uid
                if (!uid.equals(s.getInstrumentUid())) {
                    continue;
                }
            }

            ru.larionov.backend.exchange.api.model.order.OrderState mapped = mapOrderState(accountId, s);

            // keep only open/active orders
            if (mapped.status() == OrderStatus.NEW || mapped.status() == OrderStatus.PARTIALLY_FILLED) {
                result.add(mapped);
            }
        }

        return result;
    }

   private ru.larionov.backend.exchange.api.model.order.OrderState mapPostOrderState(OrderRequest req, OrderId orderId, ClientOrderId clientOrderId, PostOrderResponse resp) {
        BigDecimal requested = BigDecimal.valueOf(resp.getLotsRequested());
        BigDecimal executed = BigDecimal.valueOf(resp.getLotsExecuted());

        BigDecimal avgPrice = moneyValueToBigDecimal(resp.getExecutedOrderPrice());
        if (avgPrice == null) {
            // fallback: for not executed order, use limit
            avgPrice = req.limitPrice();
        }

        OrderFee fee = feeFromPostOrderResponse(resp);

        Instant now = Instant.now();

        return new ru.larionov.backend.exchange.api.model.order.OrderState(
                orderId,
                clientOrderId,
                req.accountId(),
                req.instrumentId(),
                req.side(),
                requested,
                executed,
                req.limitPrice(),
                avgPrice,
                fee,
                mapStatus(resp.getExecutionReportStatus()),
                now,
                now
        );
    }

    private ru.larionov.backend.exchange.api.model.order.OrderState mapOrderState(AccountId accountId, ru.tinkoff.piapi.contract.v1.OrderState s) {
        OrderId orderId = new OrderId(s.getOrderId());
        ClientOrderId clientOrderId = new ClientOrderId(s.getOrderRequestId());

        InstrumentId instrumentId = new InstrumentId(s.getInstrumentUid(), s.getFigi());

        BigDecimal requested = BigDecimal.valueOf(s.getLotsRequested());
        BigDecimal executed = BigDecimal.valueOf(s.getLotsExecuted());

        BigDecimal limitPrice = moneyValueToBigDecimal(s.getInitialOrderPrice());
        BigDecimal avgPrice = moneyValueToBigDecimal(s.getAveragePositionPrice());
        if (avgPrice == null) {
            avgPrice = moneyValueToBigDecimal(s.getExecutedOrderPrice());
        }
        if (avgPrice == null) {
            avgPrice = limitPrice;
        }

        OrderSide side = mapSideBack(s.getDirection());

        OrderFee fee = feeFromOrderState(s);

        Instant createdAt = toInstant(s.getOrderDate());
        // Раньше updatedAt слепо равнялся createdAt, из-за чего исполненный час назад
        // ордер выглядел неизменившимся с момента постановки.
        Instant updatedAt = Instant.now();

        return new ru.larionov.backend.exchange.api.model.order.OrderState(
                orderId,
                clientOrderId,
                accountId,
                instrumentId,
                side,
                requested,
                executed,
                limitPrice,
                avgPrice,
                fee,
                mapStatus(s.getExecutionReportStatus()),
                createdAt,
                updatedAt
        );
    }

    private OrderFee feeFromPostOrderResponse(PostOrderResponse response) {
        if (response.hasExecutedCommission() && response.getLotsExecuted() > 0) {
            return OrderFee.actual(
                    moneyValueToBigDecimal(response.getExecutedCommission()),
                    response.getExecutedCommission().getCurrency(),
                    CommissionSource.EXCHANGE_EXECUTED);
        }
        if (response.hasInitialCommission()) {
            return OrderFee.estimated(
                    null,
                    moneyValueToBigDecimal(response.getInitialCommission()),
                    response.getInitialCommission().getCurrency(),
                    CommissionSource.EXCHANGE_INITIAL);
        }
        return null;
    }

    private OrderFee feeFromOrderState(ru.tinkoff.piapi.contract.v1.OrderState state) {
        if (state.hasServiceCommission()) {
            BigDecimal service = moneyValueToBigDecimal(state.getServiceCommission());
            if (service != null && service.signum() != 0) {
                log.info("T-Invest serviceCommission for order {} is {} {}. Не суммирую с executedCommission, "
                                + "пока семантика брокера не проверена на живом отчёте.",
                        state.getOrderId(), service.toPlainString(), state.getServiceCommission().getCurrency());
            }
        }
        if (state.hasExecutedCommission() && state.getLotsExecuted() > 0) {
            return OrderFee.actual(
                    moneyValueToBigDecimal(state.getExecutedCommission()),
                    state.getExecutedCommission().getCurrency(),
                    CommissionSource.EXCHANGE_EXECUTED);
        }
        if (state.hasInitialCommission()) {
            return OrderFee.estimated(
                    null,
                    moneyValueToBigDecimal(state.getInitialCommission()),
                    state.getInitialCommission().getCurrency(),
                    CommissionSource.EXCHANGE_INITIAL);
        }
        return null;
    }

    private static OrderDirection mapSide(OrderSide side) {
        return switch (side) {
            case BUY -> OrderDirection.ORDER_DIRECTION_BUY;
            case SELL -> OrderDirection.ORDER_DIRECTION_SELL;
        };
    }

    private static OrderSide mapSideBack(OrderDirection d) {
        return switch (d) {
            case ORDER_DIRECTION_BUY -> OrderSide.BUY;
            case ORDER_DIRECTION_SELL -> OrderSide.SELL;
            default -> OrderSide.BUY;
        };
    }

    /**
     * ВНИМАНИЕ: аргумент игнорируется, и это не упущение, а свойство площадки.
     *
     * В контракте T-Invest 1.49.3 есть только UNSPECIFIED, DAY, FILL_AND_KILL и
     * FILL_OR_KILL — GTC отсутствует. Любая лимитная заявка живёт ровно одну
     * торговую сессию и умирает на её закрытии.
     *
     * От этого зависит вся конструкция сетки: она обязана переставлять себя при
     * каждом открытии торгов, а не полагаться на то, что вчерашние заявки ещё стоят.
     * Триггером служит событие торгового статуса из стрима.
     *
     * FILL_AND_KILL / FILL_OR_KILL сознательно не поддерживаем: сетка ставит
     * пассивные заявки и ждёт исполнения, а эти режимы означают противоположное.
     */
    private static ru.tinkoff.piapi.contract.v1.TimeInForceType mapTif(TimeInForce tif) {
        return TimeInForceType.TIME_IN_FORCE_DAY;
    }

    private static OrderStatus mapStatus(OrderExecutionReportStatus s) {
        if (s == null) {
            return OrderStatus.UNKNOWN;
        }

        return switch (s) {
            case EXECUTION_REPORT_STATUS_NEW -> OrderStatus.NEW;
            case EXECUTION_REPORT_STATUS_PARTIALLYFILL -> OrderStatus.PARTIALLY_FILLED;
            case EXECUTION_REPORT_STATUS_FILL -> OrderStatus.FILLED;
            case EXECUTION_REPORT_STATUS_CANCELLED -> OrderStatus.CANCELLED;
            case EXECUTION_REPORT_STATUS_REJECTED -> OrderStatus.REJECTED;
            default -> OrderStatus.UNKNOWN;
        };
    }

    private static BigDecimal moneyValueToBigDecimal(MoneyValue mv) {
        if (mv == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(mv.getUnits());
        BigDecimal nano = BigDecimal.valueOf(mv.getNano(), 9);
        return units.add(nano);
    }

    private static BigDecimal quotationToBigDecimal(Quotation q) {
        if (q == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        return units.add(nano);
    }

    private static Quotation toQuotation(BigDecimal v) {
        if (v == null) {
            return Quotation.getDefaultInstance();
        }
        BigDecimal scaled = v.setScale(9, RoundingMode.DOWN);
        long units = scaled.longValue();
        int nano = scaled.remainder(BigDecimal.ONE).movePointRight(9).intValue();
        return Quotation.newBuilder()
                .setUnits(units)
                .setNano(nano)
                .build();
    }

    private static Instant toInstant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }
}
