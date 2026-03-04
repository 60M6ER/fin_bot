package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exchange.tinvest.TInvestExchangeHandler;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.EventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRuntimeService {

    private final ExchangeConnectionRepository repo;
    private final TelegramNotifyService notifyService;
    private final BotRuntimeService botRuntimeService;

    private final ConcurrentHashMap<UUID, ExchangeHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RuntimeInfo> runtime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, __ -> new Object());
    }

    private ExchangeHandler createHandler(ExchangeConnectionEntity conn) {
        // Пока поддерживаем только T-Invest. Остальные биржи добавим позже.
        if (conn.getExchange() != ExchangeType.T_INVEST) {
            throw new IllegalArgumentException("Unsupported exchange: " + conn.getExchange());
        }
        return new TInvestExchangeHandler(
                conn
                // TODO: позже передадим сюда нужные зависимости/конфиг, когда появятся
        );
    }

    // ==============================
    // RESTORE ON STARTUP
    // ==============================

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActiveConnections() {
        // After restart in-memory maps are empty; restore connections that were marked active in DB.
        List<ExchangeConnectionEntity> activeConnections = repo.findAll().stream()
                .filter(ExchangeConnectionEntity::isActive)
                .toList();

        if (activeConnections.isEmpty()) {
            return;
        }

        for (ExchangeConnectionEntity c : activeConnections) {
            // Start asynchronously so that app boot is not blocked by network/API calls.
            CompletableFuture.runAsync(() -> activate(c.getId()));
        }
    }

    // ==============================
    // ACTIVATE
    // ==============================

    public void activate(UUID id) {
        synchronized (lock(id)) {

            if (handlers.containsKey(id)) {
                return; // уже активен
            }

            runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVATING, null, Instant.now()));

            ExchangeConnectionEntity conn = repo.findById(id)
                    .orElseThrow(() -> new ru.larionov.backend.exception.NotFoundException("Exchange connection not found: " + id));

            ExchangeHandler handler = null;

            try {
                handler = createHandler(conn);
                handler.start(); // поднимаем соединение и стримы
                handler.test();  // health-check авторизации

                handlers.put(id, handler);

                runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVE, null, Instant.now()));

                conn.setActive(true);
                repo.save(conn);

                botRuntimeService.onConnectionActivated(id);

                notifyService.broadcast("""
                        ✅ Подключение активировано
                        
                        Биржа: %s
                        ID: %s
                        """.formatted(conn.getExchange(), id));
                //throw new RuntimeException("MANUAL TEST");
            } catch (Exception ex) {
                log.error("ERROR from ExchangeRuntimeService activate(): {}", ex.getMessage(), ex);

                if (handler != null) {
                    try { handler.stop(); } catch (Exception ignore) {}
                }

                runtime.put(id, new RuntimeInfo(id, RuntimeState.ERROR,
                        ex.getMessage(), Instant.now()));

                conn.setActive(false);
                repo.save(conn);

                notifyService.broadcast("""
                        ❌ Ошибка активации подключения
                        
                        Биржа: %s
                        ID: %s
                        Ошибка: %s
                        """.formatted(conn.getExchange(), id, ex.getMessage()));
            }
        }
    }

    // ==============================
    // DEACTIVATE
    // ==============================

    public void deactivate(UUID id) {
        synchronized (lock(id)) {

            botRuntimeService.onConnectionDeactivating(id);

            ExchangeHandler handler = handlers.remove(id);
            if (handler != null) {
                try { handler.stop(); } catch (Exception ignore) {}
            }

            runtime.put(id, new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));

            if (!shuttingDown) {
                ExchangeConnectionEntity conn = repo.findById(id)
                        .orElseThrow(() -> new ru.larionov.backend.exception.NotFoundException("Exchange connection not found: " + id));
                conn.setActive(false);
                repo.save(conn);
            }

            if (!shuttingDown) {
                ExchangeConnectionEntity conn = repo.findById(id)
                        .orElseThrow(() -> new ru.larionov.backend.exception.NotFoundException("Exchange connection not found: " + id));
                notifyService.broadcast("""
                        ⛔ Подключение остановлено
                        
                        Биржа: %s
                        ID: %s
                        """.formatted(conn.getExchange(), id));
            }
        }
    }

    // ==============================
    // ACCESS FOR BOTS
    // ==============================

    public ExchangeHandler require(UUID id) {
        ExchangeHandler handler = handlers.get(id);
        if (handler == null) {
            throw new IllegalStateException("Exchange not active: " + id);
        }
        return handler;
    }

    public Optional<ExchangeHandler> get(UUID id) {
        return Optional.ofNullable(handlers.get(id));
    }

    public RuntimeInfo getRuntime(UUID id) {
        return runtimeInfo(id);
    }

    public RuntimeInfo runtimeInfo(UUID id) {
        return runtime.getOrDefault(id,
                new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));
    }

    public List<RuntimeInfo> listRuntime() {
        return new ArrayList<>(runtime.values());
    }
    // ==============================
    // SHUTDOWN
    // ==============================

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        for (UUID id : new ArrayList<>(handlers.keySet())) {
            try {
                deactivate(id);
            } catch (Exception ignore) {
            }
        }
    }
}
