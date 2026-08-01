package ru.larionov.backend.telegram.service;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Бот уведомлений. Создаётся через TelegramConfig, а не как @Component:
 * токен приходит из настроек в БД, доступных только после поднятия JPA.
 */
@Slf4j
public class NotificationsBot extends TelegramLongPollingBot {

    private final String token;
    private final String username;
    private final TelegramChatService chatService;

    public NotificationsBot(String token, String username, TelegramChatService chatService) {
        super(token);
        this.token = token;
        this.username = username;
        this.chatService = chatService;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update == null || !update.hasMessage() || !update.getMessage().hasText()) return;

        var msg = update.getMessage();
        var text = msg.getText().trim();

        if (!text.startsWith("/start")) return;

        var chatId = msg.getChatId();
        var from = msg.getFrom();

        chatService.upsertChat(
                chatId,
                from != null ? from.getUserName() : null,
                from != null ? from.getFirstName() : null,
                from != null ? from.getLastName() : null
        );

        send(chatId, """
                ✅ Готово! Этот чат подписан на уведомления.
                Теперь я буду присылать события по ботам/биржам.
                """);
    }

    private void send(Long chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Telegram send failed: {}", e.getMessage(), e);
        }
    }

    @Override
    public String getBotUsername() {
        return username;
    }

    @Override
    public String getBotToken() {
        return token;
    }
}
