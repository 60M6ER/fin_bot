package ru.larionov.backend.telegram.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.larionov.backend.telegram.repository.TelegramChatRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotifyService {

    private final NotificationsBot bot;
    private final TelegramChatRepository chatRepo;

    public void broadcast(String text) {
        var chats = chatRepo.findAll();
        if (chats.isEmpty()) {
            log.debug("No telegram chats registered. Skip broadcast.");
            return;
        }

        for (var chat : chats) {
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chat.getChatId().toString())
                        .text(text)
                        .build());
            } catch (TelegramApiException e) {
                log.warn("Failed to send telegram message to chatId={}: {}", chat.getChatId(), e.getMessage(), e);
            }
        }
    }

    public void sendToChat(long chatId, String text) {
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
