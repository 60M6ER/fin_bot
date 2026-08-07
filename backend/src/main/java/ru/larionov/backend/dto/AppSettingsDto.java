package ru.larionov.backend.dto;

/**
 * Настройки всей программы для UI. Токен наружу не отдаём — только маску и признак наличия.
 *
 * @param telegramActive бот действительно зарегистрирован в этом процессе. Отличается от
 *                       hasTelegramToken, когда токен сохранили, но ещё не перезапустили приложение.
 */
public record AppSettingsDto(
        boolean hasTelegramToken,
        String telegramTokenMasked,
        String telegramBotUsername,
        boolean telegramActive,
        boolean tradingEnabled,
        boolean secretsEncrypted,
        /** Валюта сводного баланса: RUB или USD. Только показ. */
        String displayCurrency,
        /** Источник курса доллара: CBR или T_INVEST. */
        String fxSource,
        /** Текущий курс доллара к рублю; null — недоступен. */
        java.math.BigDecimal usdRub,
        java.time.Instant usdRubAsOf
) {}
