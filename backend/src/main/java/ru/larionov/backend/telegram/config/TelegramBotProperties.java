package ru.larionov.backend.telegram.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Только первичное заполнение чистой базы из переменных окружения.
 * Рабочее значение живёт в app_setting — см. {@link TelegramSettings}.
 */
@ConfigurationProperties(prefix = "telegram.bot")
public record TelegramBotProperties(
        String token,
        String username
) {}
