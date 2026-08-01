package ru.larionov.backend.telegram.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import ru.larionov.backend.telegram.service.NotificationsBot;
import ru.larionov.backend.telegram.service.TelegramChatService;

@Slf4j
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

    /**
     * Регистрация — сетевой вызов к API Telegram, падающий при пустом или неверном токене.
     * Раньше это роняло весь контекст: без Telegram приложение не поднималось вообще.
     * Теперь недоступность уведомлений деградирует в warning — торговля важнее оповещений о ней.
     */
    @Bean
    public TelegramBotsApi telegramBotsApi(NotificationsBot bot, TelegramSettings settings) throws TelegramApiException {
        TelegramBotsApi api = new TelegramBotsApi(DefaultBotSession.class);

        if (!settings.usable()) {
            log.warn("Токен Telegram не задан — уведомления отключены. "
                    + "Задайте его на странице «Настройки» и перезапустите приложение.");
            return api;
        }

        try {
            api.registerBot(bot);
            settings.markRegistered();
            log.info("Telegram bot registered: {}", settings.username());
        } catch (Exception e) {
            log.warn("Не удалось зарегистрировать Telegram-бота ({}). Уведомления отключены, "
                    + "приложение продолжает работу.", e.getMessage());
        }

        return api;
    }
}
