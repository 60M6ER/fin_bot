package ru.larionov.backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotRuntimeService {

    private final BotRepository repo;
    private final TelegramNotifyService notifyService;

    /**
     * Runtime-only state (НЕ хранится в БД):
     * - handlers: активные запущенные боты
     * - runtime: текущий runtime-статус (INACTIVE/ACTIVE/ERROR)
     */
    private final ConcurrentHashMap<UUID, BotHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RuntimeInfo> runtime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /**
     * На shutdown мы должны остановить runtime, но НЕ менять persisted desired-state (isActive) в БД.
     */
    private volatile boolean shuttingDown = false;

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, __ -> new Object());
    }

    public Map<UUID, RuntimeInfo> getRuntimeAll() {
        return Map.copyOf(runtime);
    }

    public RuntimeInfo getRuntime(UUID botId) {
        return runtime.get(botId);
    }

    /**
     * Called by ExchangeRuntimeService BEFORE a connection is stopped.
     * Bots are stopped while connection is still alive so they can gracefully cleanup.
     *
     * Currently we stop all running bots because we do not yet model explicit
     * connection dependencies inside strategy_config.
     */
    public void onConnectionDeactivating(UUID connectionId) {
        log.info("Connection deactivating: {}. Stopping running bots (temporary: stop all).", connectionId);
        notifyService.broadcast("""
                ⚠️ Остановка подключения — останавливаю ботов
                
                Connection: %s
                """.formatted(connectionId));
        for (UUID botId : new ArrayList<>(handlers.keySet())) {
            try {
                stopRuntimeOnly(botId, "connection deactivating: " + connectionId);
            } catch (Exception e) {
                log.warn("Failed to stop bot {} during connection deactivating {}: {}", botId, connectionId, e.getMessage(), e);
            }
        }
    }

    /**
     * Called by ExchangeRuntimeService AFTER a connection has been activated.
     * If bots are marked as active (desired state) but were not running,
     * we attempt to start them now.
     *
     * Currently we simply try to activate all bots with isActive=true
     * that are not already running.
     */
    public void onConnectionActivated(UUID connectionId) {
        log.info("Connection activated: {}. Checking bots to start.", connectionId);
        for (BotEntity bot : repo.findAll()) {
            if (bot.isActive() && !handlers.containsKey(bot.getId())) {
                try {
                    activate(bot.getId());
                } catch (Exception e) {
                    log.warn("Failed to auto-activate bot {} after connection {} activation: {}",
                            bot.getId(), connectionId, e.getMessage(), e);
                }
            }
        }
    }

    public void activate(UUID id) {
        synchronized (lock(id)) {
            // already started
            if (handlers.containsKey(id)) {
                return;
            }

            var bot = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Bot not found: " + id));

            BotHandler handler = null;
            try {
                handler = createHandler(bot);
                handler.start();

                handlers.put(id, handler);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVE, null, Instant.now()));

                // Persist desired state
                bot.setActive(true);
                repo.save(bot);

                notifyService.broadcast("""
                        ✅ Бот активирован

                        Bot: %s
                        ID: %s
                        Strategy: %s
                        """.formatted(bot.getName(), id, bot.getStrategyType()));

                log.info("Bot activated: id={}, name={}, strategy={}", id, bot.getName(), bot.getStrategyType());

            } catch (Exception ex) {

                // best-effort stop
                if (handler != null) {
                    try {
                        handler.stop();
                    } catch (Exception stopEx) {
                        log.warn("Bot stop after failed activate failed: id={}, err={}", id, stopEx.getMessage(), stopEx);
                    }
                }

                handlers.remove(id);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ERROR, ex.getMessage(), Instant.now()));

                // Persist desired state (если активация не удалась — считаем что desired=false)
                bot.setActive(false);
                repo.save(bot);

                notifyService.broadcast("""
                        ❌ Ошибка активации бота

                        Bot: %s
                        ID: %s
                        Strategy: %s
                        Error: %s
                        """.formatted(bot.getName(), id, bot.getStrategyType(), ex.getMessage()));

                log.error("Bot activation failed: id={}, name={}, strategy={}, err={}", id, bot.getName(), bot.getStrategyType(), ex.getMessage(), ex);

                // Не пробрасываем — вызывающая сторона уже не обязана падать.
            }
        }
    }

    public void deactivate(UUID id) {
        synchronized (lock(id)) {
            BotHandler handler = handlers.remove(id);

            if (handler != null) {
                try {
                    handler.stop();
                } catch (Exception ex) {
                    log.warn("Bot stop failed: id={}, err={}", id, ex.getMessage(), ex);
                }
            }

            runtime.put(id, new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));

            // Важно: при штатном shutdown не трогаем persisted desired-state в БД.
            if (!shuttingDown) {
                var bot = repo.findById(id)
                        .orElseThrow(() -> new NotFoundException("Bot not found: " + id));
                bot.setActive(false);
                repo.save(bot);

                notifyService.broadcast("""
                        ⛔ Бот остановлен

                        Bot: %s
                        ID: %s
                        Strategy: %s
                        """.formatted(bot.getName(), id, bot.getStrategyType()));

                log.info("Bot deactivated: id={}, name={}", id, bot.getName());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        for (UUID id : new ArrayList<>(handlers.keySet())) {
            try {
                deactivate(id);
            } catch (Exception e) {
                log.warn("Bot shutdown deactivate failed: id={}, err={}", id, e.getMessage(), e);
            }
        }
    }

    private void stopRuntimeOnly(UUID botId, String reason) {
        synchronized (lock(botId)) {
            BotHandler handler = handlers.remove(botId);
            if (handler != null) {
                try {
                    handler.stop();
                } catch (Exception ex) {
                    log.warn("Bot stop failed (runtime-only): id={}, err={}", botId, ex.getMessage(), ex);
                }
            }
            runtime.put(botId, new RuntimeInfo(botId, RuntimeState.INACTIVE, reason, Instant.now()));
        }
    }

    /**
     * Фабрика хендлеров. Сейчас это каркас: стратегия + JSON-конфиг находятся в bot.
     * Позже сюда подключим StrategyFactory и реальные реализации.
     */
    private BotHandler createHandler(BotEntity bot) {
        // В текущем каркасе компилируем сервис и оставляем точку расширения.
        return new NoopBotHandler();
    }

    /**
     * Минимальный runtime-хендлер, чтобы сервис был рабочим каркасом.
     * Реальные хендлеры будут запускать StrategyEngine/тикер/подписки и т.д.
     */
    public interface BotHandler {
        void start();
        void stop();
    }

    private static class NoopBotHandler implements BotHandler {
        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }
    }
}
