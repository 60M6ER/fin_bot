package ru.larionov.backend.service;

/** Ключи настроек всей программы. Держим в одном месте, чтобы не разъезжались строки. */
public final class AppSettingKeys {

    private AppSettingKeys() {
    }

    public static final String TELEGRAM_BOT_TOKEN = "telegram.bot.token";
    public static final String TELEGRAM_BOT_USERNAME = "telegram.bot.username";

    /**
     * Глобальный стоп-кран. При false ни один бот не имеет права выставить ордер,
     * независимо от собственных настроек. Проверяется в RiskGuard на этапе исполнения.
     */
    public static final String TRADING_ENABLED = "trading.enabled";
}
