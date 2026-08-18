package ru.larionov.backend.telegram.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import ru.larionov.backend.telegram.config.TelegramSettings;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Регистрирует long-polling бота после старта приложения.
 *
 * Регистрация ходит в Telegram API и при плохой сети может висеть десятки секунд.
 * Торговля и HTTP-интерфейс не должны ждать этот вторичный канал уведомлений.
 */
@Slf4j
@Component
public class TelegramBotRegistrar {

    private final TelegramBotsApi api;
    private final NotificationsBot bot;
    private final TelegramSettings settings;
    private final ExecutorService executor;

    @Autowired
    public TelegramBotRegistrar(TelegramBotsApi api, NotificationsBot bot, TelegramSettings settings) {
        this(api, bot, settings, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "telegram-register");
            t.setDaemon(true);
            return t;
        }));
    }

    TelegramBotRegistrar(TelegramBotsApi api, NotificationsBot bot, TelegramSettings settings,
                         ExecutorService executor) {
        this.api = api;
        this.bot = bot;
        this.settings = settings;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerAfterStartup() {
        if (!settings.usable()) {
            log.warn("Токен Telegram не задан — уведомления отключены. "
                    + "Задайте его на странице «Настройки» и перезапустите приложение.");
            return;
        }
        executor.submit(this::registerSafely);
    }

    void registerSafely() {
        try {
            api.registerBot(bot);
            settings.markRegistered();
            log.info("Telegram bot registered: {}", settings.username());
        } catch (Exception e) {
            log.warn("Не удалось зарегистрировать Telegram-бота ({}). Уведомления отключены, "
                    + "приложение продолжает работу.", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
