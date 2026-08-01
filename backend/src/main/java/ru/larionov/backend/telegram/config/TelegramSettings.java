package ru.larionov.backend.telegram.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.service.AppSettingKeys;
import ru.larionov.backend.service.AppSettingService;

/**
 * Источник правды по настройкам Telegram — таблица app_setting.
 *
 * application.yaml и переменные окружения остались только как первичное заполнение
 * чистой базы: раньше рабочий токен лежал прямо в yaml и уехал в git.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramSettings {

    private final AppSettingService settings;
    private final TelegramBotProperties bootstrap;

    /**
     * Бот реально зарегистрирован в этом процессе. Отличается от usable(), когда токен
     * сохранили через UI, но приложение ещё не перезапускали: long-polling сессия
     * держит старый токен и переподключить её на лету нельзя.
     */
    private volatile boolean registered;

    public boolean isRegistered() {
        return registered;
    }

    public void markRegistered() {
        this.registered = true;
    }

    @PostConstruct
    void seedFromEnvironment() {
        // Заглушки вроде "disabled" в БД не кладём — иначе они переживут удаление переменной.
        if (looksLikeRealToken(bootstrap.token())) {
            settings.seedIfAbsent(AppSettingKeys.TELEGRAM_BOT_TOKEN, bootstrap.token(), true);
        }
        if (bootstrap.username() != null && !bootstrap.username().isBlank()) {
            settings.seedIfAbsent(AppSettingKeys.TELEGRAM_BOT_USERNAME, bootstrap.username(), false);
        }
    }

    public String token() {
        return settings.get(AppSettingKeys.TELEGRAM_BOT_TOKEN, null);
    }

    public String username() {
        return settings.get(AppSettingKeys.TELEGRAM_BOT_USERNAME, null);
    }

    /**
     * Telegram — канал уведомлений, а не часть торгового контура: его отсутствие
     * не должно мешать приложению стартовать и торговать.
     */
    public boolean usable() {
        return looksLikeRealToken(token());
    }

    public String maskedToken() {
        String t = token();
        if (t == null || t.isBlank()) {
            return "";
        }
        // Часть до двоеточия — публичный id бота, скрывать её смысла нет.
        int colon = t.indexOf(':');
        if (colon <= 0 || t.length() - colon < 5) {
            return "***";
        }
        return t.substring(0, colon + 1) + "***" + t.substring(t.length() - 3);
    }

    public static boolean looksLikeRealToken(String token) {
        return token != null
                && !token.isBlank()
                && !"disabled".equalsIgnoreCase(token.trim())
                // Токен Telegram имеет вид <bot_id>:<secret>; всё прочее — заведомо заглушка.
                && token.contains(":");
    }
}
