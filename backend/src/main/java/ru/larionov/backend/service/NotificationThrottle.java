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
 * Подавление ПОВТОРОВ в уведомлениях.
 *
 * Раньше здесь был ещё и лимит частоты, который просто выбрасывал лишние уведомления.
 * Он убран: теперь поток не режется, а склеивается в одно сообщение
 * ({@code NotificationAggregator}) — так ничего не теряется. Лимит частоты в паре
 * с агрегацией только вредил бы: выбрасывал бы события бурного момента, ради которых
 * уведомления и нужны.
 *
 * А вот дедупликация нужна и с агрегацией: залипшая ошибка повторяется из тика в тик
 * часами, и склеивать сотню одинаковых строк так же бессмысленно, как слать их по одной.
 *
 * Важно: подавление касается ТОЛЬКО уведомлений. В журнал и в консоль попадает всё
 * без исключений — иначе разбор происшествия задним числом станет невозможен.
 */
@Slf4j
@Component
public class NotificationThrottle {

    /** Повтор того же сообщения в этом окне подавляется. */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);

    public enum Decision {
        SEND,
        /** Такое же сообщение уже уходило только что. */
        SUPPRESSED_DUPLICATE
    }

    private static final class BotState {
        final Map<String, Instant> lastSentByKey = new ConcurrentHashMap<>();
        final AtomicInteger suppressedDuplicates = new AtomicInteger();
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
        if (duplicates == 0) {
            return null;
        }
        return "🔇 Скрыто повторов: %d. Полная картина — в ленте событий бота."
                .formatted(duplicates);
    }

    public List<UUID> knownBots() {
        return new ArrayList<>(states.keySet());
    }

    public void forget(UUID botId) {
        states.remove(botId);
    }
}
