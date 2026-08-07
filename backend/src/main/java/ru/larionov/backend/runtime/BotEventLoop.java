package ru.larionov.backend.runtime;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.strategy.StrategyCommand;

import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Событийный цикл одного бота: один поток разбирает одну очередь.
 *
 * Зачем: колбэки стримов приходят с потоков SDK, параллельно и из разных подписок.
 * Сводя их в одну очередь с одним рабочим потоком, мы избавляем код стратегии
 * от блокировок и гонок — он всегда исполняется последовательно.
 *
 * Политика очереди намеренно разная по типам событий:
 * <ul>
 *   <li><b>цены</b> схлопываются до последней: при всплеске котировок промежуточные
 *       значения бесполезны, а очередь ими забивается;</li>
 *   <li><b>события ордеров</b> не теряются никогда — потеря означает разъезд с биржей;
 *       при переполнении бот уходит в ошибку, а не молча продолжает;</li>
 *   <li><b>реконнект и статус торгов</b> обрабатываются вне очереди по приоритету:
 *       действовать по устаревшему состоянию опаснее, чем опоздать с ценой.</li>
 * </ul>
 */
@Slf4j
public final class BotEventLoop implements AutoCloseable {

    /** Меньше — важнее. */
    private static final int PRIORITY_CONTROL = 0;
    private static final int PRIORITY_ORDER = 1;
    private static final int PRIORITY_PRICE = 2;
    private static final int PRIORITY_TICK = 3;

    private enum Kind { RECONNECT, TRADING_STATUS, COMMAND, ORDER, PRICE, TICK }

    private record Event(int priority, long seq, Kind kind, Object payload) implements Comparable<Event> {
        @Override
        public int compareTo(Event o) {
            int byPriority = Integer.compare(priority, o.priority);
            // Внутри приоритета — строгий FIFO: порядок событий по одному ордеру значим.
            return byPriority != 0 ? byPriority : Long.compare(seq, o.seq);
        }
    }

    private final UUID botId;
    private final BotEventListener listener;
    private final int capacity;
    private final Consumer<String> onFatal;

    private final PriorityBlockingQueue<Event> queue = new PriorityBlockingQueue<>();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();

    /** Последняя цена живёт вне очереди — так схлопывание не требует прохода по ней. */
    private final AtomicReference<LastPrice> latestPrice = new AtomicReference<>();
    private final AtomicBoolean priceQueued = new AtomicBoolean(false);

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final int maxConsecutiveFailures;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final Thread worker;

    public BotEventLoop(UUID botId, BotEventListener listener, int capacity,
                        int maxConsecutiveFailures, Consumer<String> onFatal) {
        this.botId = botId;
        this.listener = listener;
        this.capacity = capacity;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.onFatal = onFatal;
        this.worker = new Thread(this::run, "bot-loop-" + shortId(botId));
        this.worker.setDaemon(true);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker.start();
        }
    }

    // ==============================
    // SUBMIT
    // ==============================

    /**
     * Цена схлопывается: в очереди всегда не больше одного маркера, а значение
     * перезаписывается. Быстрый рынок не может вытеснить события ордеров.
     */
    public void submitPrice(LastPrice price) {
        if (!running.get() || price == null) {
            return;
        }
        latestPrice.set(price);
        if (priceQueued.compareAndSet(false, true)) {
            offer(new Event(PRIORITY_PRICE, sequence.incrementAndGet(), Kind.PRICE, null));
        }
    }

    /** Событие ордера не теряется никогда: потеря = разъезд состояния с биржей. */
    public void submitOrderUpdate(OrderState state) {
        if (!running.get() || state == null) {
            return;
        }
        offer(new Event(PRIORITY_ORDER, sequence.incrementAndGet(), Kind.ORDER, state));
    }

    public void submitTradingStatus(TradingStatusEvent event) {
        if (!running.get() || event == null) {
            return;
        }
        offer(new Event(PRIORITY_CONTROL, sequence.incrementAndGet(), Kind.TRADING_STATUS, event));
    }

    /**
     * Команда оператора идёт с управляющим приоритетом: человек уже видит,
     * что бот стоит, и ждать своей очереди за котировками ей незачем.
     */
    public void submitCommand(StrategyCommand command) {
        if (!running.get() || command == null) {
            return;
        }
        offer(new Event(PRIORITY_CONTROL, sequence.incrementAndGet(), Kind.COMMAND, command));
    }

    public void submitReconnect() {
        if (!running.get()) {
            return;
        }
        offer(new Event(PRIORITY_CONTROL, sequence.incrementAndGet(), Kind.RECONNECT, null));
    }

    public void submitTick() {
        if (!running.get()) {
            return;
        }
        offer(new Event(PRIORITY_TICK, sequence.incrementAndGet(), Kind.TICK, null));
    }

    private void offer(Event event) {
        if (queued.get() >= capacity) {
            // Переполнение означает, что бот не успевает обрабатывать события.
            // Продолжать в таком состоянии нельзя: решения будут приниматься
            // по заведомо устаревшей картине.
            fail("очередь событий переполнена (" + capacity + ")");
            return;
        }
        queued.incrementAndGet();
        queue.offer(event);
    }

    public int queueSize() {
        return queued.get();
    }

    // ==============================
    // WORKER
    // ==============================

    private void run() {
        log.info("Bot event loop started: botId={}", botId);
        while (running.get()) {
            Event event;
            try {
                event = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                continue;
            }
            queued.decrementAndGet();
            dispatch(event);
        }
        log.info("Bot event loop stopped: botId={}", botId);
    }

    private void dispatch(Event event) {
        try {
            switch (event.kind()) {
                case PRICE -> {
                    // Снимаем флаг ДО чтения значения: цена, пришедшая во время обработки,
                    // поставит новый маркер и не потеряется.
                    priceQueued.set(false);
                    LastPrice price = latestPrice.getAndSet(null);
                    if (price != null) {
                        listener.onPrice(price);
                    }
                }
                case COMMAND -> listener.onCommand((StrategyCommand) event.payload());
                case ORDER -> listener.onOrderUpdate((OrderState) event.payload());
                case TRADING_STATUS -> listener.onTradingStatus((TradingStatusEvent) event.payload());
                case RECONNECT -> listener.onStreamReconnect();
                case TICK -> listener.onTick();
            }
            consecutiveFailures.set(0);
        } catch (Exception e) {
            // Одна ошибка цикл не роняет — иначе бот замолчал бы, оставаясь формально
            // запущенным. Но серия подряд означает, что дальше он будет только
            // накапливать расхождение с биржей, и такого бота надо гасить.
            int failures = consecutiveFailures.incrementAndGet();
            log.error("Bot {} event handling failed ({}), {} подряд: {}",
                    botId, event.kind(), failures, e.getMessage(), e);

            if (failures >= maxConsecutiveFailures) {
                fail(failures + " ошибок обработки событий подряд, последняя: " + e.getMessage());
            }
        }
    }

    /** Сообщаем наружу один раз: повторные вызовы только зашумили бы Telegram. */
    private void fail(String reason) {
        if (!failed.compareAndSet(false, true)) {
            return;
        }
        log.error("Bot {} event loop fatal: {}", botId, reason);
        if (onFatal != null) {
            try {
                onFatal.accept(reason);
            } catch (Exception e) {
                log.error("onFatal handler failed: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            // Даём текущему событию досчитаться, чтобы не бросать бота на полушаге.
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.clear();
        queued.set(0);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
