package ru.larionov.backend.exchange.tinvest;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.OrdersStreamService;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderDirection;
import ru.tinkoff.piapi.contract.v1.OrderExecutionReportStatus;
import ru.tinkoff.piapi.contract.v1.OrderTrade;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamRequest;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamResponse;
import ru.ttech.piapi.core.connector.resilience.ResilienceServerSideStreamWrapper;
import ru.ttech.piapi.core.connector.resilience.ResilienceServerSideStreamWrapperConfiguration;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;
import ru.ttech.piapi.core.impl.orders.OrderStateStreamWrapperConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Поток состояний ордеров T-Invest.
 *
 * Главное свойство: {@code OrderStateStreamResponse.OrderState.getOrderRequestId()} — это
 * наш clientOrderId, тот самый, что уходил в PostOrderRequest.setOrderId(). Поэтому событие
 * сопоставляется с записью журнала напрямую, и поллинг статусов ордеров не нужен.
 *
 * Авто-переподключение обеспечивает {@link ResilienceServerSideStreamWrapper}; нам важно
 * узнать о факте переподключения, чтобы запустить сверку.
 */
@Slf4j
public final class TInvestOrdersStreamService implements OrdersStreamService {

    private final StreamServiceStubFactory streamFactory;
    private final ScheduledExecutorService scheduler;

    private final StreamHealthTracker healthTracker = new StreamHealthTracker();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();
    private final List<Consumer<OrderState>> handlers = new CopyOnWriteArrayList<>();

    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile ResilienceServerSideStreamWrapper<OrderStateStreamRequest, OrderStateStreamResponse> wrapper;
    private volatile AccountId accountId;

    TInvestOrdersStreamService(StreamServiceStubFactory streamFactory, ScheduledExecutorService scheduler) {
        this.streamFactory = streamFactory;
        this.scheduler = scheduler;
    }

    @Override
    public void subscribeOrderStates(AccountId accountId, Consumer<OrderState> handler) {
        if (closed.get()) {
            throw new IllegalStateException("Orders stream is closed");
        }
        handlers.add(handler);

        // Стрим один на подключение: он и так отдаёт все ордера счёта.
        if (!subscribed.compareAndSet(false, true)) {
            return;
        }
        this.accountId = accountId;

        // Методы билдера возвращают родительский тип, поэтому и переменную объявляем им:
        // newResilienceServerSideStream принимает именно ResilienceServerSideStreamWrapperConfiguration.
        ResilienceServerSideStreamWrapperConfiguration<OrderStateStreamRequest, OrderStateStreamResponse> config =
                OrderStateStreamWrapperConfiguration
                        .builder(scheduler)
                        .addOnResponseListener(this::handleResponse)
                        .addOnConnectListener(this::handleConnect)
                        .build();

        this.wrapper = streamFactory.newResilienceServerSideStream(config);
        this.wrapper.subscribe(OrderStateStreamRequest.newBuilder()
                .addAccounts(accountId.value())
                .build());

        log.info("Order state stream subscribed for account {}", accountId.value());
    }

    private void handleConnect() {
        boolean isReconnect = healthTracker.markConnected();
        if (!isReconnect) {
            log.info("Order state stream connected");
            return;
        }

        log.warn("Order state stream RECONNECTED. За время разрыва события потеряны — нужна сверка.");
        for (Runnable h : reconnectHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                log.error("Reconnect handler failed: {}", e.getMessage(), e);
            }
        }
    }

    private void handleResponse(OrderStateStreamResponse response) {
        healthTracker.markEvent();

        // В потоке приходят ещё ping и подтверждения подписки — они нам не интересны.
        if (response == null || !response.hasOrderState()) {
            return;
        }

        OrderState state;
        try {
            state = toDomain(response.getOrderState());
        } catch (Exception e) {
            log.error("Не удалось разобрать событие ордера: {}", e.getMessage(), e);
            return;
        }

        for (Consumer<OrderState> h : handlers) {
            try {
                h.accept(state);
            } catch (Exception e) {
                // Падение обработчика не должно рвать стрим.
                log.error("Order state handler failed: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void onReconnect(Runnable handler) {
        reconnectHandlers.add(handler);
    }

    @Override
    public StreamHealth health() {
        return healthTracker.snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        var w = this.wrapper;
        this.wrapper = null;
        if (w != null) {
            try {
                w.disconnect();
            } catch (Exception e) {
                log.warn("Order state stream disconnect failed: {}", e.getMessage());
            }
        }
        healthTracker.markClosed();
        handlers.clear();
        reconnectHandlers.clear();
    }

    // ==============================
    // MAPPING
    // ==============================

    private OrderState toDomain(OrderStateStreamResponse.OrderState s) {
        // orderRequestId — наш clientOrderId. Именно по нему событие ложится на журнал.
        ClientOrderId clientOrderId = s.hasOrderRequestId() && !s.getOrderRequestId().isBlank()
                ? new ClientOrderId(s.getOrderRequestId())
                : null;

        BigDecimal requested = BigDecimal.valueOf(s.getLotsRequested());
        BigDecimal executed = BigDecimal.valueOf(s.getLotsExecuted());

        BigDecimal limitPrice = money(s.hasInitialOrderPrice() ? s.getInitialOrderPrice() : null);
        BigDecimal avgPrice = averageExecutedPrice(s);
        if (avgPrice == null) {
            avgPrice = limitPrice;
        }

        Instant createdAt = s.hasCreatedAt()
                ? Instant.ofEpochSecond(s.getCreatedAt().getSeconds(), s.getCreatedAt().getNanos())
                : Instant.now();
        Instant updatedAt = s.hasCompletionTime()
                ? Instant.ofEpochSecond(s.getCompletionTime().getSeconds(), s.getCompletionTime().getNanos())
                : Instant.now();

        return new OrderState(
                new OrderId(s.getOrderId()),
                clientOrderId,
                new AccountId(s.getAccountId()),
                new InstrumentId(s.getInstrumentUid(), null),
                mapSide(s.getDirection()),
                requested,
                executed,
                limitPrice,
                avgPrice,
                null, // фактическую комиссию считаем по сделкам, не здесь
                mapStatus(s.getExecutionReportStatus()),
                createdAt,
                updatedAt
        );
    }

    private static BigDecimal money(MoneyValue mv) {
        if (mv == null) {
            return null;
        }
        return BigDecimal.valueOf(mv.getUnits()).add(BigDecimal.valueOf(mv.getNano(), 9));
    }

    static BigDecimal averageExecutedPrice(OrderStateStreamResponse.OrderState state) {
        BigDecimal total = BigDecimal.ZERO;
        long shares = 0;
        for (OrderTrade trade : state.getTradesList()) {
            if (!trade.hasPrice() || trade.getQuantity() <= 0) {
                continue;
            }
            BigDecimal price = BigDecimal.valueOf(trade.getPrice().getUnits())
                    .add(BigDecimal.valueOf(trade.getPrice().getNano(), 9));
            total = total.add(price.multiply(BigDecimal.valueOf(trade.getQuantity())));
            shares += trade.getQuantity();
        }
        if (shares > 0) {
            return total.divide(BigDecimal.valueOf(shares), 9, RoundingMode.HALF_UP);
        }
        if (!state.hasExecutedOrderPrice()) {
            return null;
        }

        // В OrderStateStream executedOrderPrice фактически приходит стоимость лота.
        // Для внутренней модели нужна цена одной бумаги: ledger сам умножит её на lotSize.
        return money(state.getExecutedOrderPrice())
                .divide(BigDecimal.valueOf(Math.max(1, state.getLotSize())), 9, RoundingMode.HALF_UP);
    }

    private static OrderSide mapSide(OrderDirection d) {
        return d == OrderDirection.ORDER_DIRECTION_SELL ? OrderSide.SELL : OrderSide.BUY;
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
}
