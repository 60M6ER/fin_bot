package ru.larionov.backend.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.model.ExchangeConnectionActivatedEvent;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRuntimeService {

    private final ExchangeConnectionRepository repo;
    private final TelegramNotifyService notifyService;
    private final BotRuntimeService botRuntimeService;
    private final ExchangeConnectionContextResolver contextResolver;
    private final ApplicationEventPublisher events;
    private final List<ExchangeHandlerFactory> handlerFactories;

    private final ConcurrentHashMap<UUID, ExchangeHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RuntimeInfo> runtime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();

    /**
     * Активация подключения — блокирующие gRPC-вызовы. Общий ForkJoinPool для такого
     * не годится: он рассчитан на короткие CPU-задачи.
     */
    private final ExecutorService startupExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "exchange-activate");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean shuttingDown = false;

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, __ -> new Object());
    }

    /**
     * Реестр фабрик по типу биржи. Собирается Spring'ом из бинов
     * {@link ExchangeHandlerFactory}, поэтому новая биржа добавляется своей фабрикой,
     * без правки этого класса.
     */
    private Map<ExchangeType, ExchangeHandlerFactory> factoryByExchange;

    @PostConstruct
    void indexFactories() {
        factoryByExchange = handlerFactories.stream()
                .collect(Collectors.toMap(ExchangeHandlerFactory::exchange, f -> f));
        log.info("Поддерживаемые биржи: {}", factoryByExchange.keySet());
    }

    private ExchangeHandler createHandler(ExchangeConnectionEntity conn) {
        ExchangeHandlerFactory factory = factoryByExchange.get(conn.getExchange());
        if (factory == null) {
            throw new IllegalArgumentException("Биржа пока не поддерживается: " + conn.getExchange());
        }
        // Секреты расшифровываются здесь, на границе: дальше адаптер работает
        // с готовым контекстом и про шифрование ничего не знает.
        return factory.create(contextResolver.resolve(conn));
    }

    /** Биржи, для которых есть адаптер. Список подключений предлагает только их. */
    public Set<ExchangeType> supportedExchanges() {
        return factoryByExchange.keySet();
    }

    // ==============================
    // RESTORE ON STARTUP
    // ==============================

    @EventListener(ApplicationReadyEvent.class)
    public void restoreActiveConnections() {
        // После рестарта in-memory мапы пусты; поднимаем то, что помечено active в БД.
        List<ExchangeConnectionEntity> activeConnections = repo.findAllByActiveTrueOrderByNameAsc();

        if (activeConnections.isEmpty()) {
            return;
        }

        log.info("Restoring {} active exchange connection(s) after startup", activeConnections.size());
        for (ExchangeConnectionEntity c : activeConnections) {
            // Асинхронно, чтобы сеть не блокировала старт приложения.
            startupExecutor.submit(() -> start(c.getId(), false));
        }
    }

    // ==============================
    // ACTIVATE / DEACTIVATE
    // ==============================

    /** Команда пользователя «запустить подключение»: фиксирует намерение и поднимает runtime. */
    public void activate(UUID id) {
        synchronized (lock(id)) {
            if (handlers.containsKey(id)) {
                return;
            }

            ExchangeConnectionEntity conn = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));

            // Намерение пользователя фиксируем ДО попытки: сбой сети не должен
            // навсегда выключать подключение — супервизор повторит.
            if (!conn.isActive()) {
                conn.setActive(true);
                repo.save(conn);
            }

            start(id, true);
        }
    }

    /**
     * Попытка поднять runtime подключения без изменения desired-state.
     * Используется восстановлением при старте и супервизором.
     *
     * @param userInitiated отличает команду пользователя от автоматической попытки —
     *                      влияет только на то, шумим ли мы в Telegram.
     */
    public void start(UUID id, boolean userInitiated) {
        synchronized (lock(id)) {
            if (handlers.containsKey(id)) {
                return;
            }

            ExchangeConnectionEntity conn = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));

            RuntimeState previousState = runtimeInfo(id).state();
            runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVATING, null, Instant.now()));

            ExchangeHandler handler = null;
            try {
                handler = createHandler(conn);
                handler.start(); // поднимаем соединение и стримы
                handler.test();  // health-check авторизации

                handlers.put(id, handler);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ACTIVE, null, Instant.now()));

                // Каскад: хендлер уже в мапе, поэтому require() у ботов отработает.
                botRuntimeService.onConnectionActivated(id);

                // Подписчики обязаны возвращаться мгновенно: мы всё ещё внутри
                // synchronized(lock(id)), и долгая работа тут застопорила бы deactivate,
                // супервизор и остановку приложения.
                events.publishEvent(new ExchangeConnectionActivatedEvent(id, conn.getExchange()));

                notifyService.broadcast("""
                        ✅ Подключение активировано

                        Биржа: %s
                        Имя: %s
                        ID: %s
                        """.formatted(conn.getExchange(), conn.getName(), id));

                log.info("Exchange connection started: id={}, name={}, exchange={}",
                        id, conn.getName(), conn.getExchange());

            } catch (Exception ex) {
                if (handler != null) {
                    try {
                        handler.stop();
                    } catch (Exception stopEx) {
                        log.warn("Handler stop after failed start failed: id={}, err={}",
                                id, stopEx.getMessage(), stopEx);
                    }
                }

                handlers.remove(id);
                runtime.put(id, new RuntimeInfo(id, RuntimeState.ERROR, ex.getMessage(), Instant.now()));

                // desired-state НЕ трогаем — иначе ночной сбой API навсегда гасит подключение
                // вместе со всеми его ботами, и утром ничего не работает.

                if (userInitiated || previousState != RuntimeState.ERROR) {
                    notifyService.broadcast("""
                            ❌ Ошибка активации подключения

                            Биржа: %s
                            Имя: %s
                            ID: %s
                            Ошибка: %s
                            """.formatted(conn.getExchange(), conn.getName(), id, ex.getMessage()));
                }

                log.error("Exchange connection start failed: id={}, name={}, err={}",
                        id, conn.getName(), ex.getMessage(), ex);
            }
        }
    }

    /** Команда пользователя «остановить подключение»: снимает намерение и гасит runtime вместе с ботами. */
    public void deactivate(UUID id) {
        synchronized (lock(id)) {
            // Боты глушим ДО остановки хендлера, пока подключение ещё живо.
            botRuntimeService.onConnectionDeactivating(id);

            ExchangeHandler handler = handlers.remove(id);
            if (handler != null) {
                try {
                    handler.stop();
                } catch (Exception ex) {
                    log.warn("Exchange handler stop failed: id={}, err={}", id, ex.getMessage(), ex);
                }
            }

            runtime.put(id, new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));

            if (shuttingDown) {
                return;
            }

            ExchangeConnectionEntity conn = repo.findById(id)
                    .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));
            conn.setActive(false);
            repo.save(conn);

            notifyService.broadcast("""
                    ⛔ Подключение остановлено

                    Биржа: %s
                    Имя: %s
                    ID: %s
                    """.formatted(conn.getExchange(), conn.getName(), id));

            log.info("Exchange connection deactivated: id={}, name={}", id, conn.getName());
        }
    }

    // ==============================
    // ACCESS FOR BOTS
    // ==============================

    public ExchangeHandler require(UUID id) {
        ExchangeHandler handler = handlers.get(id);
        if (handler == null) {
            throw new IllegalStateException(
                    "Подключение не активно: " + id + ". Запустите подключение перед запуском бота.");
        }
        return handler;
    }

    public Optional<ExchangeHandler> get(UUID id) {
        return Optional.ofNullable(handlers.get(id));
    }

    /**
     * Любое поднятое подключение к указанной бирже.
     *
     * Нужно синхронизации справочника: список инструментов одинаков для всех токенов,
     * поэтому годится любой живой клиент. Боевое предпочитаем песочнице — у песочных
     * токенов бывает урезанный доступ. Порядок доразрешается по connectionId, чтобы
     * выбор был детерминированным между вызовами.
     */
    public Optional<ExchangeHandler> findRunningByExchange(ExchangeType type) {
        return handlers.values().stream()
                .filter(h -> h.exchangeType() == type)
                .min(Comparator.comparing((ExchangeHandler h) -> isSandbox(h) ? 1 : 0)
                        .thenComparing(ExchangeHandler::connectionId));
    }

    private static boolean isSandbox(ExchangeHandler handler) {
        return handler.sandbox();
    }

    public boolean isRunning(UUID id) {
        return handlers.containsKey(id);
    }

    public RuntimeInfo getRuntime(UUID id) {
        return runtimeInfo(id);
    }

    public RuntimeInfo runtimeInfo(UUID id) {
        return runtime.getOrDefault(id, new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now()));
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
            } catch (Exception e) {
                log.warn("Exchange shutdown deactivate failed: id={}, err={}", id, e.getMessage(), e);
            }
        }
        startupExecutor.shutdownNow();
        try {
            startupExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
