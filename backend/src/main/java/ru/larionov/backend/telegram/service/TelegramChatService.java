package ru.larionov.backend.telegram.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.telegram.entity.TelegramChat;
import ru.larionov.backend.telegram.repository.TelegramChatRepository;

@Service
@RequiredArgsConstructor
public class TelegramChatService {

    private final TelegramChatRepository repo;

    @Transactional
    public TelegramChat upsertChat(long chatId, String username, String firstName, String lastName) {
        return repo.findByChatId(chatId)
                .map(existing -> {
                    existing.setUsername(username);
                    existing.setFirstName(firstName);
                    existing.setLastName(lastName);
                    return existing;
                })
                .orElseGet(() -> repo.save(TelegramChat.builder()
                        .chatId(chatId)
                        .username(username)
                        .firstName(firstName)
                        .lastName(lastName)
                        .build()));
    }
}
