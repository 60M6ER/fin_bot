package ru.larionov.backend.telegram.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Дублирует ERROR-логи приложения в Telegram.
 *
 * Раньше уходил КАЖДЫЙ такой лог. С работающим торговым циклом это гарантированный
 * спам: одна повторяющаяся ошибка в стриме способна за минуту выдать сотни сообщений
 * и похоронить под собой то, ради чего уведомления и нужны.
 *
 * Ограничители намеренно живут прямо здесь, а не в Spring-бине: аппендер логбэка
 * поднимается раньше контекста и обязан работать даже когда приложение не поднялось.
 */
public class TelegramLogAppender extends AppenderBase<ILoggingEvent> {

    /** Повтор той же ошибки в этом окне не отправляется. */
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(10);

    /** Потолок сообщений в минуту на весь аппендер. */
    private static final int MAX_PER_MINUTE = 5;

    private static final ThreadLocal<Boolean> sending = ThreadLocal.withInitial(() -> false);

    private final Map<String, Instant> lastSentByKey = new ConcurrentHashMap<>();
    private final Deque<Instant> recentSends = new ArrayDeque<>();

    @Override
    protected void append(ILoggingEvent event) {
        // Защита от рекурсии: отправка сама может залогировать ошибку.
        if (sending.get()) return;
        if (event.getLevel() != Level.ERROR) return;

        var ctx = SpringContextHolder.get();
        if (ctx == null) return;

        // События ботов уже уходят в Telegram через BotEventService со своим
        // ограничителем — дублировать их отсюда не нужно.
        if (event.getLoggerName() != null
                && event.getLoggerName().startsWith("ru.larionov.backend.service.BotEventService")) {
            return;
        }

        String key = event.getLoggerName() + '|' + event.getFormattedMessage();
        if (!allow(key)) {
            return;
        }

        try {
            sending.set(true);
            var notifyService = ctx.getBean(TelegramNotifyService.class);

            notifyService.broadcast("""
                    🚨 Ошибка приложения

                    %s
                    %s
                    """.formatted(shortLogger(event.getLoggerName()), event.getFormattedMessage()));
        } catch (Exception e) {
            // Аппендер логов не имеет права ронять или засорять приложение. Типичный случай —
            // ошибка на старте, когда контекст ещё не поднят и бина уведомлений просто нет.
            // Молча пропускаем: сама ошибка уже ушла в консоль обычным аппендером.
        } finally {
            sending.set(false);
        }
    }

    private synchronized boolean allow(String key) {
        Instant now = Instant.now();

        Instant last = lastSentByKey.get(key);
        if (last != null && Duration.between(last, now).compareTo(DEDUP_WINDOW) < 0) {
            return false;
        }

        Instant cutoff = now.minus(Duration.ofMinutes(1));
        while (!recentSends.isEmpty() && recentSends.peekFirst().isBefore(cutoff)) {
            recentSends.pollFirst();
        }
        if (recentSends.size() >= MAX_PER_MINUTE) {
            return false;
        }

        recentSends.addLast(now);
        lastSentByKey.put(key, now);

        // Кэш ключей не должен расти без предела в долгоживущем процессе.
        if (lastSentByKey.size() > 500) {
            lastSentByKey.entrySet().removeIf(e -> Duration.between(e.getValue(), now).compareTo(DEDUP_WINDOW) >= 0);
        }
        return true;
    }

    private static String shortLogger(String loggerName) {
        if (loggerName == null) return "";
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
    }
}
