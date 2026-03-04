package ru.larionov.backend.telegram.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.larionov.backend.telegram.config.TelegramBotProperties;

@Slf4j
@Component
public class NotificationsBot extends TelegramLongPollingBot {

    private final TelegramBotProperties props;
    private final TelegramChatService chatService;

    public NotificationsBot(TelegramBotProperties props, TelegramChatService chatService) {
        super(props.token());
        this.props = props;
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
        return props.username();
    }

    @Override
    public String getBotToken() {
        return props.token();
    }
}
