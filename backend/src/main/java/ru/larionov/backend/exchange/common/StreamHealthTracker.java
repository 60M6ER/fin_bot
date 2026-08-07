package ru.larionov.backend.exchange.common;

import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Счётчики живости стрима. Пишутся с потоков SDK, читаются из UI и сторожевого таймера.
 *
 * Общий для всех адаптеров: живость стрима устроена одинаково у любой биржи,
 * а вот последствия разрыва — нет, и именно поэтому {@link #markConnected()}
 * отличает первое подключение от переподключения.
 */
public final class StreamHealthTracker {

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean firstConnect = new AtomicBoolean(true);
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastConnectAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicLong reconnects = new AtomicLong();

    /**
     * @return true, если это переподключение, а не первое подключение.
     *         Разница важна: после первого подключения синхронизировать ещё нечего,
     *         а после переподключения обязательна сверка — за время разрыва
     *         события потерялись безвозвратно.
     */
    public boolean markConnected() {
        lastConnectAt.set(Instant.now());
        lastError.set(null);
        connected.set(true);

        boolean isReconnect = !firstConnect.compareAndSet(true, false);
        if (isReconnect) {
            reconnects.incrementAndGet();
        }
        return isReconnect;
    }

    public void markEvent() {
        lastEventAt.set(Instant.now());
        connected.set(true);
    }

    public void markError(String message) {
        lastError.set(message);
        connected.set(false);
    }

    public void markClosed() {
        connected.set(false);
    }

    public StreamHealth snapshot() {
        return new StreamHealth(
                connected.get(),
                lastEventAt.get(),
                lastConnectAt.get(),
                reconnects.get(),
                lastError.get()
        );
    }
}
