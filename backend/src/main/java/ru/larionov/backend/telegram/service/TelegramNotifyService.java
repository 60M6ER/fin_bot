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

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotifyService {

    private final NotificationsBot bot;
    private final TelegramChatRepository chatRepo;
    private final TelegramSettings settings;

    private NotificationAggregator aggregator;

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
        try {
            bot.execute(SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send telegram message to chatId={}: {}", chatId, e.getMessage(), e);
        }
    }
}
