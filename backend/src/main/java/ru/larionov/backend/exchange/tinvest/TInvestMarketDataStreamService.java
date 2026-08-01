package ru.larionov.backend.exchange.tinvest;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.MarketDataStreamService;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.tinkoff.piapi.contract.v1.OrderBookType;
import ru.tinkoff.piapi.contract.v1.SecurityTradingStatus;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;
import ru.ttech.piapi.core.connector.streaming.listeners.OnConnectListener;
import ru.ttech.piapi.core.impl.marketdata.MarketDataStreamManager;
import ru.ttech.piapi.core.impl.marketdata.subscription.Instrument;
import ru.ttech.piapi.core.impl.marketdata.wrapper.LastPriceWrapper;
import ru.ttech.piapi.core.impl.marketdata.wrapper.OrderBookWrapper;
import ru.ttech.piapi.core.impl.marketdata.wrapper.TradingStatusWrapper;
import ru.ttech.piapi.core.impl.wrapper.OnConnectEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Потоковые рыночные данные T-Invest поверх {@link MarketDataStreamManager}.
 *
 * Менеджер сам раскладывает подписки по нескольким стримам, соблюдая лимиты
 * из ConnectorConfiguration, — мультиплексирование руками писать не нужно.
 */
@Slf4j
public final class TInvestMarketDataStreamService implements MarketDataStreamService {

    private final ReconnectAwareManager manager;
    private final StreamHealthTracker healthTracker = new StreamHealthTracker();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();

    /** Подписки помним, чтобы снять их все разом при остановке. */
    private final List<Runnable> unsubscribeActions = new CopyOnWriteArrayList<>();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    TInvestMarketDataStreamService(StreamServiceStubFactory streamFactory,
                                   ExecutorService callbackExecutor,
                                   ScheduledExecutorService scheduler) {
        this.manager = new ReconnectAwareManager(streamFactory, callbackExecutor, scheduler);
        this.manager.registerOnConnect(this::handleConnect);
    }

    /**
     * addOnConnectListener в MarketDataStreamManager объявлен protected, поэтому
     * добираемся до него наследником. Это единственный способ узнать о переподключении,
     * а без него после разрыва бот действовал бы по устаревшему состоянию.
     */
    private static final class ReconnectAwareManager extends MarketDataStreamManager {
        ReconnectAwareManager(StreamServiceStubFactory f, ExecutorService e, ScheduledExecutorService s) {
            super(f, e, s);
        }

        void registerOnConnect(OnConnectListener<OnConnectEvent> listener) {
            addOnConnectListener(listener);
        }
    }

    private void handleConnect(OnConnectEvent event) {
        boolean isReconnect = healthTracker.markConnected();
        if (!isReconnect) {
            log.info("Market data stream connected: {}", event.id());
            return;
        }

        log.warn("Market data stream RECONNECTED ({}). Требуется пересинхронизация состояния.", event.id());
        for (Runnable h : reconnectHandlers) {
            try {
                h.run();
            } catch (Exception e) {
                log.error("Reconnect handler failed: {}", e.getMessage(), e);
            }
        }
    }

    private void ensureStarted() {
        if (closed.get()) {
            throw new IllegalStateException("Market data stream is closed");
        }
        if (started.compareAndSet(false, true)) {
            manager.start();
        }
    }

    @Override
    public void subscribeLastPrice(Set<InstrumentId> instruments, Consumer<LastPrice> handler) {
        ensureStarted();
        Set<Instrument> subs = toInstruments(instruments);
        if (subs.isEmpty()) {
            return;
        }

        manager.subscribeLastPrices(subs, wrapper -> {
            healthTracker.markEvent();
            deliver(handler, toLastPrice(wrapper), "lastPrice");
        });
        unsubscribeActions.add(() -> manager.unsubscribeLastPrices(subs));
    }

    @Override
    public void subscribeOrderBook(Set<InstrumentId> instruments, int depth, Consumer<OrderBook> handler) {
        ensureStarted();
        Set<Instrument> subs = instruments.stream()
                .map(InstrumentId::primary)
                .filter(uid -> uid != null && !uid.isBlank())
                .map(uid -> new Instrument(uid, depth, OrderBookType.ORDERBOOK_TYPE_ALL))
                .collect(Collectors.toSet());
        if (subs.isEmpty()) {
            return;
        }

        manager.subscribeOrderBooks(subs, wrapper -> {
            healthTracker.markEvent();
            deliver(handler, toOrderBook(wrapper, depth), "orderBook");
        });
        unsubscribeActions.add(() -> manager.unsubscribeOrderBooks(subs));
    }

    @Override
    public void subscribeTradingStatus(Set<InstrumentId> instruments, Consumer<TradingStatusEvent> handler) {
        ensureStarted();
        Set<Instrument> subs = toInstruments(instruments);
        if (subs.isEmpty()) {
            return;
        }

        manager.subscribeTradingStatuses(subs, wrapper -> {
            healthTracker.markEvent();
            deliver(handler, toTradingStatus(wrapper), "tradingStatus");
        });
        unsubscribeActions.add(() -> manager.unsubscribeTradingStatuses(subs));
    }

    /**
     * Исключение из обработчика не должно рвать стрим: SDK вызывает нас на своём потоке,
     * и упавший listener способен утащить за собой всю доставку событий.
     */
    private <T> void deliver(Consumer<T> handler, T event, String kind) {
        if (event == null) {
            return;
        }
        try {
            handler.accept(event);
        } catch (Exception e) {
            log.error("Market data handler failed ({}): {}", kind, e.getMessage(), e);
        }
    }

    @Override
    public void unsubscribeAll() {
        for (Runnable action : unsubscribeActions) {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("Unsubscribe failed: {}", e.getMessage());
            }
        }
        unsubscribeActions.clear();
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
        try {
            unsubscribeAll();
        } catch (Exception e) {
            log.warn("Unsubscribe on close failed: {}", e.getMessage());
        }
        try {
            manager.shutdown();
        } catch (Exception e) {
            log.warn("Market data stream shutdown failed: {}", e.getMessage());
        }
        healthTracker.markClosed();
        reconnectHandlers.clear();
    }

    // ==============================
    // MAPPING
    // ==============================

    private static Set<Instrument> toInstruments(Set<InstrumentId> ids) {
        return ids.stream()
                .map(InstrumentId::primary)
                .filter(uid -> uid != null && !uid.isBlank())
                .map(Instrument::new)
                .collect(Collectors.toSet());
    }

    private static LastPrice toLastPrice(LastPriceWrapper w) {
        return new LastPrice(
                new InstrumentId(w.getInstrumentUid(), w.getFigi()),
                new Price(w.getPrice(), null),
                toInstant(w.getTime())
        );
    }

    private static OrderBook toOrderBook(OrderBookWrapper w, int depth) {
        List<OrderBookLevel> bids = new ArrayList<>();
        for (OrderBookWrapper.OrderWrapper o : w.getBids()) {
            bids.add(new OrderBookLevel(new Price(o.getPrice(), null), BigDecimal.valueOf(o.getQuantity())));
        }
        List<OrderBookLevel> asks = new ArrayList<>();
        for (OrderBookWrapper.OrderWrapper o : w.getAsks()) {
            asks.add(new OrderBookLevel(new Price(o.getPrice(), null), BigDecimal.valueOf(o.getQuantity())));
        }

        return new OrderBook(
                new InstrumentId(w.getInstrumentUid(), w.getFigi()),
                depth,
                bids,
                asks,
                w.getLimitUp() == null ? null : new Price(w.getLimitUp(), null),
                w.getLimitDown() == null ? null : new Price(w.getLimitDown(), null),
                toInstant(w.getTime())
        );
    }

    private static TradingStatusEvent toTradingStatus(TradingStatusWrapper w) {
        SecurityTradingStatus status = w.getTradingStatus();

        // tradable — идут ли торги вообще; limitOrdersAvailable — можно ли ставить лимитки.
        // Для сетки значим именно второй признак: в аукционе торги идут,
        // но лимитная заявка может не приниматься.
        boolean tradable = status == SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING;

        return new TradingStatusEvent(
                new InstrumentId(w.getInstrumentUid(), w.getFigi()),
                tradable,
                w.isLimitOrderAvailable(),
                status == null ? null : status.name(),
                toInstant(w.getTime())
        );
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? Instant.now() : time.toInstant(ZoneOffset.UTC);
    }
}
