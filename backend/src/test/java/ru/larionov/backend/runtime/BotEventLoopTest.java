package ru.larionov.backend.runtime;

import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.strategy.StrategyCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты политики очереди — то, от чего зависит, увидит ли бот все исполнения
 * своих ордеров. Сети не требуют.
 */
class BotEventLoopTest {

    private static final UUID BOT = UUID.randomUUID();

    private static LastPrice price(String value) {
        return new LastPrice(
                new InstrumentId("uid-1", null),
                new Price(new BigDecimal(value), "RUB"),
                Instant.now());
    }

    private static OrderState order(String clientOrderId) {
        return new OrderState(
                new OrderId("exch-" + clientOrderId),
                new ClientOrderId(clientOrderId),
                new AccountId("acc"),
                new InstrumentId("uid-1", null),
                OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ZERO,
                new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.NEW, Instant.now(), Instant.now());
    }

    /** Каркас слушателя: считает вызовы и умеет притормаживать обработку. */
    private static final class RecordingListener implements BotEventListener {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<LastPrice> prices = new CopyOnWriteArrayList<>();
        volatile CountDownLatch gate;
        volatile RuntimeException throwOnOrder;

        @Override public void onPrice(LastPrice p) {
            prices.add(p);
            events.add("price:" + p.price().value().toPlainString());
        }

        @Override public void onOrderUpdate(OrderState s) {
            awaitGate();
            if (throwOnOrder != null) {
                events.add("order-failed:" + s.clientOrderId().value());
                throw throwOnOrder;
            }
            events.add("order:" + s.clientOrderId().value());
        }

        @Override public void onTradingStatus(TradingStatusEvent e) { events.add("status"); }
        @Override public void onStreamReconnect() { events.add("reconnect"); }
        @Override public void onCommand(StrategyCommand c) { events.add("command:" + c); }
        @Override public void onTick() { events.add("tick"); }

        private void awaitGate() {
            CountDownLatch g = gate;
            if (g == null) return;
            try {
                g.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Условие не выполнилось за отведённое время");
    }

    @Test
    void ordersAreNeverDropped() throws Exception {
        RecordingListener listener = new RecordingListener();
        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 5, r -> { })) {
            loop.start();

            for (int i = 0; i < 200; i++) {
                loop.submitOrderUpdate(order("co-" + i));
            }

            awaitUntil(() -> listener.events.stream().filter(e -> e.startsWith("order:")).count() == 200);
        }
    }

    @Test
    void pricesAreCoalescedToTheLatest() throws Exception {
        RecordingListener listener = new RecordingListener();
        // Держим воркер на первом событии, пока накидываем цены.
        listener.gate = new CountDownLatch(1);

        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 5, r -> { })) {
            loop.start();

            // Занимаем воркер обработкой ордера.
            loop.submitOrderUpdate(order("blocker"));
            Thread.sleep(100);

            for (int i = 1; i <= 100; i++) {
                loop.submitPrice(price(String.valueOf(i)));
            }

            listener.gate.countDown();

            awaitUntil(() -> !listener.prices.isEmpty());
            Thread.sleep(200);

            // Схлопнулись: обработана одна цена, и это последняя из поставленных.
            assertEquals(1, listener.prices.size(),
                    "Промежуточные котировки должны схлопнуться в одну");
            assertEquals(new BigDecimal("100"), listener.prices.get(0).price().value());
        }
    }

    @Test
    void controlEventsOvertakePriceAndTick() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.gate = new CountDownLatch(1);

        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 5, r -> { })) {
            loop.start();

            loop.submitOrderUpdate(order("blocker"));
            Thread.sleep(100);

            // Порядок постановки: тик, цена, реконнект.
            loop.submitTick();
            loop.submitPrice(price("42"));
            loop.submitReconnect();

            listener.gate.countDown();
            awaitUntil(() -> listener.events.size() >= 4);
            Thread.sleep(200);

            List<String> after = listener.events.subList(1, listener.events.size());
            assertEquals("reconnect", after.get(0),
                    "Реконнект обязан обгонять цену и тик: действовать по устаревшему состоянию опаснее");
            assertTrue(after.indexOf("price:42") < after.indexOf("tick"),
                    "Цена важнее сторожевого тика");
        }
    }

    @Test
    void queueOverflowReportsFatalInsteadOfSilentlyDropping() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.gate = new CountDownLatch(1);
        AtomicReference<String> fatal = new AtomicReference<>();

        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 10, 5, fatal::set)) {
            loop.start();

            loop.submitOrderUpdate(order("blocker"));
            Thread.sleep(100);

            for (int i = 0; i < 50; i++) {
                loop.submitOrderUpdate(order("overflow-" + i));
            }

            awaitUntil(() -> fatal.get() != null);
            assertTrue(fatal.get().contains("переполнена"),
                    "Переполнение должно сообщаться наружу, а не молча терять события");

            listener.gate.countDown();
        }
    }

    @Test
    void singleFailureDoesNotStopTheLoop() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.throwOnOrder = new RuntimeException("boom");
        AtomicReference<String> fatal = new AtomicReference<>();

        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 5, fatal::set)) {
            loop.start();

            loop.submitOrderUpdate(order("bad-1"));
            awaitUntil(() -> listener.events.contains("order-failed:bad-1"));

            // Цикл жив: следующее событие обрабатывается.
            listener.throwOnOrder = null;
            loop.submitOrderUpdate(order("good"));
            awaitUntil(() -> listener.events.contains("order:good"));

            assertNull(fatal.get(), "Одна ошибка не должна гасить бота");
        }
    }

    @Test
    void consecutiveFailuresStopTheBot() throws Exception {
        RecordingListener listener = new RecordingListener();
        listener.throwOnOrder = new RuntimeException("boom");
        AtomicReference<String> fatal = new AtomicReference<>();
        AtomicInteger fatalCount = new AtomicInteger();

        try (BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 3, reason -> {
            fatal.set(reason);
            fatalCount.incrementAndGet();
        })) {
            loop.start();

            for (int i = 0; i < 10; i++) {
                loop.submitOrderUpdate(order("bad-" + i));
            }

            awaitUntil(() -> fatal.get() != null);
            assertTrue(fatal.get().contains("подряд"));

            Thread.sleep(200);
            assertEquals(1, fatalCount.get(),
                    "О фатальной ошибке сообщаем один раз, иначе Telegram превратится в пулемёт");
        }
    }

    @Test
    void submissionsAfterCloseAreIgnored() {
        RecordingListener listener = new RecordingListener();
        BotEventLoop loop = new BotEventLoop(BOT, listener, 1000, 5, r -> { });
        loop.start();
        loop.close();

        loop.submitOrderUpdate(order("after-close"));
        loop.submitPrice(price("1"));

        assertEquals(0, loop.queueSize());
    }
}
