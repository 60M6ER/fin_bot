package ru.larionov.backend.exchange.api.model.stream;

import java.time.Duration;
import java.time.Instant;

/**
 * Состояние стрима. Нужно и человеку в UI, и сторожевому таймеру бота:
 * молчащий стрим внешне неотличим от спокойного рынка, а разница принципиальна —
 * во втором случае бот работает, в первом он ослеп.
 */
public record StreamHealth(
        boolean connected,
        Instant lastEventAt,
        Instant lastConnectAt,
        long reconnectCount,
        String lastError
) {

    public static StreamHealth disconnected() {
        return new StreamHealth(false, null, null, 0, null);
    }

    /** Сколько времени не было ни одного события. null, если событий не было вовсе. */
    public Duration silenceFor(Instant now) {
        return lastEventAt == null ? null : Duration.between(lastEventAt, now);
    }
}
