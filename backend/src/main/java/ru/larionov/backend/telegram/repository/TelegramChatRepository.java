package ru.larionov.backend.telegram.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.larionov.backend.telegram.entity.TelegramChat;

import java.util.Optional;
import java.util.UUID;

public interface TelegramChatRepository extends JpaRepository<TelegramChat, UUID> {
    Optional<TelegramChat> findByChatId(Long chatId);
    boolean existsByChatId(Long chatId);
}
