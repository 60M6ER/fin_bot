package ru.larionov.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ограничитель уведомлений в Telegram.
 *
 * Зачем: при поллинге частоту событий естественно ограничивал период опроса.
 * На стриме такого ограничителя нет — залипший в ошибке бот способен отправить
 * сотни сообщений в минуту и утопить в них то единственное, ради которого
 * уведомления и нужны.
 *
 * Важно: троттлинг касается ТОЛЬКО уведомлений. В журнал и в консоль попадает
 * всё без исключений — иначе разбор происшествия задним числом станет невозможен.
 */
@Slf4j
@Component
public class NotificationThrottle {

    /** Повтор того же сообщения в этом окне подавляется. */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    /** Потолок уведомлений на бота в минуту. */
    private static final int MAX_PER_MINUTE = 10;

    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    public enum Decision {
        SEND,
        /** Такое же сообщение уже уходило только что. */
        SUPPRESSED_DUPLICATE,
        /** Превышен лимит частоты. */
        SUPPRESSED_RATE
    }

    private static final class BotState {
        final Map<String, Instant> lastSentByKey = new ConcurrentHashMap<>();
        final List<Instant> recentSends = new ArrayList<>();
        final AtomicInteger suppressedDuplicates = new AtomicInteger();
        final AtomicInteger suppressedByRate = new AtomicInteger();
    }

    private final Map<UUID, BotState> states = new ConcurrentHashMap<>();

    public Decision decide(UUID botId, String dedupKey) {
        BotState state = states.computeIfAbsent(botId, __ -> new BotState());
        Instant now = Instant.now();

        Instant lastSent = state.lastSentByKey.get(dedupKey);
        if (lastSent != null && Duration.between(lastSent, now).compareTo(DEDUP_WINDOW) < 0) {
            state.suppressedDuplicates.incrementAndGet();
            return Decision.SUPPRESSED_DUPLICATE;
        }

        synchronized (state.recentSends) {
            Instant cutoff = now.minus(RATE_WINDOW);
            state.recentSends.removeIf(t -> t.isBefore(cutoff));

            if (state.recentSends.size() >= MAX_PER_MINUTE) {
                state.suppressedByRate.incrementAndGet();
                return Decision.SUPPRESSED_RATE;
            }
            state.recentSends.add(now);
        }

        state.lastSentByKey.put(dedupKey, now);
        return Decision.SEND;
    }

    /**
     * Сводка о скрытом. Отправляется отдельно, чтобы пользователь знал: тишина
     * в Telegram не означает, что ничего не происходило.
     *
     * @return текст сводки или null, если скрывать было нечего
     */
    public String drainSummary(UUID botId) {
        BotState state = states.get(botId);
        if (state == null) {
            return null;
        }
        int duplicates = state.suppressedDuplicates.getAndSet(0);
        int rate = state.suppressedByRate.getAndSet(0);

        if (duplicates == 0 && rate == 0) {
            return null;
        }

        StringBuilder sb = new StringBuilder("🔇 Часть уведомлений скрыта: ");
        if (duplicates > 0) {
            sb.append(duplicates).append(" повтор(ов)");
        }
        if (rate > 0) {
            if (duplicates > 0) {
                sb.append(", ");
            }
            sb.append(rate).append(" из-за лимита частоты");
        }
        sb.append(". Полная картина — в ленте событий бота.");
        return sb.toString();
    }

    public List<UUID> knownBots() {
        return new ArrayList<>(states.keySet());
    }

    public void forget(UUID botId) {
        states.remove(botId);
    }
}
