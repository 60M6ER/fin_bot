package ru.larionov.backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.runtime.StrategyBotHandler;
import ru.larionov.backend.runtime.StrategyBotHandlerFactory;
import ru.larionov.backend.strategy.StrategySnapshot;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BotRuntimeService {

    private final BotRepository repo;
    private final TelegramNotifyService notifyService;
    private final ObjectProvider<StrategyBotHandlerFactory> handlerFactory;
    private final BotEventService eventService;

    /**
     * Runtime-only state (НЕ хранится в БД):
     * - handlers: активные запущенные боты
     * - runtime: текущий runtime-статус (INACTIVE/ACTIVE/ERROR)
     *
     * Важно: runtime-статус и desired-state (BotEntity.active) — разные вещи.
     * desired-state отражает намерение пользователя и меняется только его командой;
     * сбой активации отражается в runtime, а не затирает намерение.
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

    public boolean isRunning(UUID botId) {
        return handlers.containsKey(botId);
    }

    /** Длина очереди событий бота. Растущая очередь означает, что он не успевает. */
    public int queueSize(UUID botId) {
        BotHandler h = handlers.get(botId);
        return h instanceof StrategyBotHandler s ? s.queueSize() : 0;
    }

    public Optional<StrategySnapshot> strategySnapshot(UUID botId) {
        BotHandler handler = handlers.get(botId);
        return handler instanceof StrategyBotHandler strategyHandler
                ? strategyHandler.strategySnapshot()
                : Optional.empty();
    }

    // ==============================
    // CASCADE FROM CONNECTION
    // ==============================

    /**
     * Вызывается ExchangeRuntimeService ПЕРЕД остановкой подключения.
     * Боты останавливаются пока подключение ещё живо, чтобы успеть корректно завершиться.
     *
     * Останавливаем только ботов этого подключения: у соседних подключений
     * свои боты, и они не должны страдать.
     */
    public void onConnectionDeactivating(UUID connectionId) {
        List<BotEntity> bots = repo.findAllByExchangeConnectionIdOrderByNameAsc(connectionId);

        List<UUID> running = bots.stream()
                .map(BotEntity::getId)
                .filter(handlers::containsKey)
                .toList();

        if (running.isEmpty()) {
            log.info("Connection deactivating: {}. No running bots to stop.", connectionId);
            return;
        }

        log.info("Connection deactivating: {}. Stopping {} bot(s) of this connection.", connectionId, running.size());
        notifyService.broadcast("""
                ⚠️ Остановка подключения — останавливаю ботов

                Connection: %s
                Ботов: %d
                """.formatted(connectionId, running.size()));

        for (UUID botId : running) {
            try {
                stopRuntimeOnly(botId, "connection deactivating: " + connectionId);
            } catch (Exception e) {
                log.warn("Failed to stop bot {} during connection deactivating {}: {}",
                        botId, connectionId, e.getMessage(), e);
            }
        }
    }

    /**
     * Вызывается ExchangeRuntimeService ПОСЛЕ успешной активации подключения.
     * Поднимаем ботов именно этого подключения, у которых desired-state = active.
     */
    public void onConnectionActivated(UUID connectionId) {
        List<BotEntity> bots = repo.findAllByExchangeConnectionIdAndActiveTrueOrderByNameAsc(connectionId);

        if (bots.isEmpty()) {
            log.info("Connection activated: {}. No active bots to start.", connectionId);
            return;
        }

        log.info("Connection activated: {}. Starting {} bot(s).", connectionId, bots.size());
        for (BotEntity bot : bots) {
            if (handlers.containsKey(bot.getId())) {
                continue;
            }
            try {
                start(bot.getId(), false);
            } catch (Exception e) {
                log.warn("Failed to auto-start bot {} after connection {} activation: {}",
                        bot.getId(), connectionId, e.getMessage(), e);
            }
        }
    }

    // ==============================
    // ACTIVATE / DEACTIVATE
    // ==============================

    /**
     * Команда пользователя «запустить бота».
     * Фиксирует намерение в БД и пытается поднять runtime.
     */
    public void activate(UUID id) {
        synchronized (lock(id)) {
            if (handlers.containsKey(id)) {
                return;
            }

            BotEntity bot = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Bot not found: " + id));

            // Намерение пользователя фиксируем ДО попытки старта: даже если старт не удастся,
            // желаемое состояние остаётся active и супервизор повторит попытку.
            if (!bot.isActive()) {
                bot.setActive(true);
                repo.save(bot);
            }

            start(id, true);
        }
    }

    /**
     * Попытка поднять runtime бота без изменения desired-state.
     * Используется и каскадом от подключения, и супервизором при повторных попытках.
     *
     * @param userInitiated отличает команду пользователя от автоматической попытки —
     *                      влияет только на то, шумим ли мы в Telegram.
     */
    public void start(UUID id, boolean userInitiated) {
        synchronized (lock(id)) {
            if (handlers.containsKey(id)) {
                return;
            }

            BotEntity bot = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Bot not found: " + id));

            RuntimeState previousState = runtimeInfo(id).state();
            runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVATING, null, Instant.now()));

            BotHandler handler = null;
            try {
                handler = createHandler(bot);
                handler.start();

                handlers.put(id, handler);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVE, null, Instant.now()));

                // Уведомление шлёт сам хендлер через BotEventService (событие BOT_STARTED):
                // там оно проходит троттлинг и содержит осмысленные детали.
                // Дублировать его здесь означало бы два сообщения на каждое действие.

                log.info("Bot started: id={}, name={}, strategy={}", id, bot.getName(), bot.getStrategyType());

            } catch (Exception ex) {
                if (handler != null) {
                    try {
                        handler.stop();
                    } catch (Exception stopEx) {
                        log.warn("Bot stop after failed start failed: id={}, err={}", id, stopEx.getMessage(), stopEx);
                    }
                }

                handlers.remove(id);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ERROR, ex.getMessage(), Instant.now()));

                // В журнал бота — чтобы причина осталась в ленте событий, а не только
                // в runtime-статусе, который перетрётся следующей попыткой.
                eventService.emit(id, BotEventLevel.ERROR, BotEventType.ERROR,
                        "Не удалось запустить бота: " + ex.getMessage());

                // desired-state НЕ трогаем: пользователь хотел, чтобы бот работал.
                // Иначе одна временная ошибка навсегда выключала бы бота.

                // Шумим только если это новая проблема, иначе супервизор со своими
                // повторами превратит Telegram в пулемёт.
                if (userInitiated || previousState != RuntimeState.ERROR) {
                    notifyService.broadcast("""
                            ❌ Ошибка запуска бота

                            Bot: %s
                            ID: %s
                            Strategy: %s
                            Error: %s
                            """.formatted(bot.getName(), id, bot.getStrategyType(), ex.getMessage()));
                }

                log.error("Bot start failed: id={}, name={}, strategy={}, err={}",
                        id, bot.getName(), bot.getStrategyType(), ex.getMessage(), ex);
            }
        }
    }

    /** Команда пользователя «остановить бота»: снимает намерение и гасит runtime. */
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

            // При штатном shutdown не трогаем persisted desired-state в БД.
            if (shuttingDown) {
                return;
            }

            BotEntity bot = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Bot not found: " + id));
            bot.setActive(false);
            repo.save(bot);

            // Событие BOT_STOPPED (с отчётом о снятых заявках) шлёт сам хендлер.
            // Здесь второе сообщение было бы дублем — именно его вы и видели в Telegram.

            log.info("Bot deactivated: id={}, name={}", id, bot.getName());
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

    /** Остановка runtime без изменения desired-state (каскад от подключения). */
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

    public RuntimeInfo runtimeInfo(UUID id) {
        return runtime.getOrDefault(id, new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));
    }

    /**
     * Собирает реальный движок бота: стратегия, гейтвей, подписки на стримы.
     *
     * Фабрика приходит через ObjectProvider, чтобы разорвать цикл зависимостей:
     * она зависит от ExchangeRuntimeService, а тот — от этого сервиса.
     */
    private BotHandler createHandler(BotEntity bot) {
        return handlerFactory.getObject().create(
                bot,
                reason -> stopPermanently(bot.getId(), reason),
                () -> stopRuntimeOnly(bot.getId(), "фатальный сбой движка"));
    }

    private void stopPermanently(UUID botId, String reason) {
        deactivate(botId);
        runtime.put(botId, new RuntimeInfo(botId, RuntimeState.INACTIVE, reason, Instant.now()));
        log.error("Bot permanently stopped: id={}, reason={}", botId, reason);
    }

    public interface BotHandler {
        void start();
        void stop();
    }
}
