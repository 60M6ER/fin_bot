package ru.larionov.backend.telegram.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.larionov.backend.telegram.service.NotificationsBot;
import ru.larionov.backend.telegram.service.TelegramChatService;

@EnableConfigurationProperties(TelegramBotProperties.class)
@Configuration
public class TelegramConfig {

    /**
     * Токен читается из настроек в БД один раз при поднятии контекста.
     * Смена токена через UI требует перезапуска приложения: библиотека long-polling
     * держит сессию, открытую со старым токеном, и переподключить её на лету нельзя.
     */
    @Bean
    public NotificationsBot notificationsBot(TelegramSettings settings, TelegramChatService chatService) {
        String token = settings.usable() ? settings.token() : "";
        String username = settings.username() == null ? "" : settings.username();
        return new NotificationsBot(token, username, chatService);
    }

    /** Создание API не ходит в сеть; регистрация бота запускается отдельно после старта приложения. */
    @Bean
    public TelegramBotsApi telegramBotsApi() throws TelegramApiException {
        return new TelegramBotsApi(DefaultBotSession.class);
    }
}
