package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Глобальный стоп-кран торговли.
 *
 * При выключении ни один бот не имеет права выставить ордер, независимо от собственных
 * настроек и от того, запущен он или нет. Проверяется в ExecutionGateway перед каждым
 * ордером — то есть выключение действует немедленно, не дожидаясь остановки ботов.
 *
 * Дефолт — включено: осознанным действием пользователя является сам запуск бота,
 * а этот тумблер нужен как аварийный, а не как ещё одна галочка при настройке.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingSwitch {

    private final AppSettingService settings;

    public boolean isEnabled() {
        return settings.getBoolean(AppSettingKeys.TRADING_ENABLED, true);
    }

    public void set(boolean enabled) {
        settings.set(AppSettingKeys.TRADING_ENABLED, Boolean.toString(enabled), false);
        log.warn("Глобальный стоп-кран торговли переключён: trading.enabled={}", enabled);
    }

    /** Бросает, если торговля запрещена. Точка вызова — перед выставлением ордера. */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new IllegalStateException(
                    "Торговля остановлена глобальным стоп-краном (Настройки → Торговля разрешена).");
        }
    }
}
