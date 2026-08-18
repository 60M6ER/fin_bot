package ru.larionov.backend.telegram.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.larionov.backend.telegram.config.TelegramSettings;
import ru.larionov.backend.telegram.repository.TelegramChatRepository;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotifyService {

    private static final int FAILURES_BEFORE_PAUSE = 3;
    private static final Duration BASE_PAUSE = Duration.ofSeconds(30);
    private static final Duration MAX_PAUSE = Duration.ofMinutes(10);

    private final NotificationsBot bot;
    private final TelegramChatRepository chatRepo;
    private final TelegramSettings settings;

    private NotificationAggregator aggregator;
    private final Object failureLock = new Object();
    private int consecutiveFailures;
    private Instant pausedUntil;

    @PostConstruct
    void init() {
        aggregator = new NotificationAggregator(this::sendNow);
    }

    @PreDestroy
    void close() {
        if (aggregator != null) {
            aggregator.shutdown();
        }
    }

    /**
     * Обычное уведомление: копится и уходит вместе с соседними одним сообщением.
     *
     * События идут пачками — запуск нескольких ботов, каскад при остановке подключения,
     * серия исполнений на движении цены. Десять отдельных сообщений в такой момент
     * читать невозможно, а именно тогда читать и хочется.
     */
    public void broadcast(String text) {
        if (!usable(text)) {
            return;
        }
        aggregator.submit(text);
    }

    /**
     * Уведомление, которое обязано прийти отдельным сообщением и немедленно.
     *
     * Для ошибок: слипшись с housekeeping-строками, ошибка теряется среди них,
     * а её как раз и нужно увидеть первой.
     */
    public void broadcastIsolated(String text) {
        if (!usable(text)) {
            return;
        }
        sendNow(text);
    }

    public void sendToChat(long chatId, String text) {
        if (!settings.usable()) {
            log.debug("Telegram disabled. Skip sendToChat.");
            return;
        }
        if (isPaused()) {
            log.debug("Telegram delivery paused. Skip sendToChat.");
            return;
        }
        send(chatId, text);
    }

    private boolean usable(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (!settings.usable()) {
            log.debug("Telegram disabled. Skip broadcast.");
            return false;
        }
        if (isPaused()) {
            log.debug("Telegram delivery paused. Skip broadcast.");
            return false;
        }
        return true;
    }

    private void sendNow(String text) {
        var chats = chatRepo.findAll();
        if (chats.isEmpty()) {
            log.debug("No telegram chats registered. Skip broadcast.");
            return;
        }
        for (var chat : chats) {
            send(chat.getChatId(), text);
        }
    }

    private void send(long chatId, String text) {
        if (isPaused()) {
            log.debug("Telegram delivery paused. Skip send.");
            return;
        }
        try {
            bot.execute(SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .build());
            markSendSuccess();
        } catch (TelegramApiException e) {
            markSendFailure(chatId, e);
        }
    }

    private boolean isPaused() {
        synchronized (failureLock) {
            if (pausedUntil == null) {
                return false;
            }
            if (Instant.now().isBefore(pausedUntil)) {
                return true;
            }
            pausedUntil = null;
            return false;
        }
    }

    private void markSendSuccess() {
        synchronized (failureLock) {
            consecutiveFailures = 0;
            pausedUntil = null;
        }
    }

    private void markSendFailure(long chatId, TelegramApiException e) {
        int failures;
        Duration pause = null;
        synchronized (failureLock) {
            consecutiveFailures++;
            failures = consecutiveFailures;
            if (failures >= FAILURES_BEFORE_PAUSE) {
                pause = BASE_PAUSE.multipliedBy(1L << Math.min(failures - FAILURES_BEFORE_PAUSE, 8));
                if (pause.compareTo(MAX_PAUSE) > 0) {
                    pause = MAX_PAUSE;
                }
                pausedUntil = Instant.now().plus(pause);
            }
        }

        if (pause == null) {
            log.warn("Failed to send telegram message to chatId={} ({} of {} before pause): {}",
                    chatId, failures, FAILURES_BEFORE_PAUSE, rootMessage(e));
            log.debug("Telegram send failure details", e);
            return;
        }

        log.warn("Telegram delivery paused for {}s after {} consecutive failure(s): {}",
                pause.toSeconds(), failures, rootMessage(e));
        log.debug("Telegram send failure details", e);
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }
}
