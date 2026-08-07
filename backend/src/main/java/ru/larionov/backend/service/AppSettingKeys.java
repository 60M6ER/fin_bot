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

    /**
     * Валюта, в которой показывается СВОДНЫЙ баланс: RUB или USD.
     *
     * Только показ. Бюджеты ботов и все риск-лимиты остаются в валюте котировки
     * своего инструмента и от этой настройки не зависят.
     */
    public static final String DISPLAY_CURRENCY = "display.currency";

    /** Откуда берём курс доллара: CBR или T_INVEST. */
    public static final String FX_SOURCE = "fx.source";
}
