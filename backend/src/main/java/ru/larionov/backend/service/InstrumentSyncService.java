package ru.larionov.backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentSnapshot;
import ru.larionov.backend.model.ExchangeConnectionActivatedEvent;
import ru.larionov.backend.model.InstrumentSyncStatus;
import ru.larionov.backend.repository.InstrumentRepository;
import ru.larionov.backend.repository.InstrumentUpsertDao;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фоновое наполнение справочника инструментов.
 *
 * Ходит к бирже через любое поднятое подключение: список инструментов одинаков для всех
 * токенов. Отсюда единственное принципиальное ограничение фичи — на свежей базе, где ни одно
 * подключение ни разу не поднимали, справочник пуст. Обойти это нельзя: список инструментов
 * находится за авторизованным токеном брокера.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentSyncService {

    /** Насколько свежим считается справочник, чтобы не тратить сеть на повторную выгрузку. */
    private static final Duration FRESH_FOR = Duration.ofHours(12);

    private final ExchangeRuntimeService runtimeService;
    private final InstrumentRepository repo;
    private final InstrumentUpsertDao dao;
    private final TelegramNotifyService notifyService;

    private final ConcurrentHashMap<ExchangeType, InstrumentSyncStatus> statuses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ExchangeType, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    /**
     * Однопоточный: две параллельные выгрузки одной биржи бессмысленны, а нагружать
     * общий пул многосекундными gRPC-вызовами нельзя.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "instrument-sync");
        t.setDaemon(true);
        return t;
    });

    // ==============================
    // ТРИГГЕРЫ
    // ==============================

    /**
     * Подключение поднялось — самое подходящее время наполнить справочник.
     *
     * Метод обязан вернуться мгновенно: событие публикуется изнутри synchronized-блока
     * ExchangeRuntimeService.start(), и многосекундная выгрузка здесь застопорила бы
     * деактивацию, супервизор и остановку приложения.
     */
    @EventListener
    public void onConnectionActivated(ExchangeConnectionActivatedEvent event) {
        submit(event.exchange(), false);
    }

    /**
     * Периодическое обновление. ISO-длительности, а не cron: у джобы нет привязки
     * ко времени суток, зато есть требование не стартовать вместе с приложением.
     */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT6H")
    public void scheduledSync() {
        for (ExchangeType type : ExchangeType.values()) {
            if (runtimeService.findRunningByExchange(type).isPresent()) {
                submit(type, false);
            }
        }
    }

    /** Ручное обновление из UI. Проверки делаются синхронно, чтобы пользователь увидел отказ. */
    public InstrumentSyncStatus requestSync(ExchangeType exchange) {
        if (exchange == null) {
            throw new IllegalArgumentException("Не указана биржа");
        }
        if (runtimeService.findRunningByExchange(exchange).isEmpty()) {
            throw new IllegalStateException(
                    "Нет активного подключения к " + exchange
                            + ". Запустите подключение, чтобы обновить справочник.");
        }
        if (isRunning(exchange)) {
            throw new IllegalStateException("Обновление справочника " + exchange + " уже выполняется.");
        }

        // Статус ставим здесь, а не в фоновой задаче: иначе ответ на POST успевал бы
        // уйти раньше, чем задача стартует, и клиент получал бы пустое тело.
        InstrumentSyncStatus started = InstrumentSyncStatus.started(exchange, Instant.now());
        statuses.put(exchange, started);

        submit(exchange, true);
        return started;
    }

    public List<InstrumentSyncStatus> statuses() {
        return statuses.values().stream()
                .sorted(Comparator.comparing(s -> s.exchange().name()))
                .toList();
    }

    public InstrumentSyncStatus status(ExchangeType exchange) {
        return statuses.get(exchange);
    }

    private boolean isRunning(ExchangeType exchange) {
        AtomicBoolean flag = inFlight.get(exchange);
        return flag != null && flag.get();
    }

    private void submit(ExchangeType exchange, boolean force) {
        try {
            executor.submit(() -> syncExchange(exchange, force));
        } catch (Exception e) {
            // Пул уже остановлен (идёт shutdown) — это не повод шуметь.
            log.debug("Не удалось поставить синхронизацию справочника в очередь: {}", e.toString());
        }
    }

    // ==============================
    // САМА СИНХРОНИЗАЦИЯ
    // ==============================

    void syncExchange(ExchangeType exchange, boolean force) {
        AtomicBoolean flag = inFlight.computeIfAbsent(exchange, __ -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            log.debug("Синхронизация справочника {} уже идёт — пропускаем", exchange);
            return;
        }

        try {
            if (!force && isFresh(exchange)) {
                log.debug("Справочник {} свежий — синхронизация не нужна", exchange);
                return;
            }

            Optional<ExchangeHandler> handler = runtimeService.findRunningByExchange(exchange);
            if (handler.isEmpty()) {
                log.debug("Нет активного подключения к {} — синхронизация справочника отложена", exchange);
                return;
            }

            run(exchange, handler.get(), force);

        } catch (Exception e) {
            log.error("Синхронизация справочника {} упала: {}", exchange, e.getMessage(), e);
        } finally {
            flag.set(false);
        }
    }

    private void run(ExchangeType exchange, ExchangeHandler handler, boolean force) {
        Instant startedAt = Instant.now();
        boolean wasHealthy = wasHealthy(exchange);
        statuses.put(exchange, InstrumentSyncStatus.started(exchange, startedAt));

        Set<InstrumentKind> synced = EnumSet.noneOf(InstrumentKind.class);
        Set<InstrumentKind> failed = EnumSet.noneOf(InstrumentKind.class);
        List<String> errors = new ArrayList<>();
        int upserted = 0;

        // Каждый тип тянем и пишем отдельно: провал одного не должен обнулять остальные.
        for (InstrumentKind kind : InstrumentKind.values()) {
            List<InstrumentSnapshot> batch;
            try {
                batch = handler.client().instruments().listAll(Set.of(kind));
            } catch (UnsupportedOperationException e) {
                continue; // адаптер этот тип не умеет — это не ошибка
            } catch (Exception e) {
                failed.add(kind);
                errors.add(kind + ": " + brief(e));
                log.warn("Не удалось выгрузить {} с {}: {}", kind, exchange, e.getMessage());
                continue;
            }

            if (batch.isEmpty()) {
                continue;
            }

            try {
                upserted += dao.upsertChunked(exchange, batch, startedAt);
                synced.add(kind);
            } catch (Exception e) {
                failed.add(kind);
                errors.add(kind + ": " + brief(e));
                log.warn("Не удалось записать {} по {}: {}", kind, exchange, e.getMessage(), e);
            }
        }

        // Свип — только по успешно выгруженным типам и только после всех чанков.
        int deactivated = synced.isEmpty() ? 0 : dao.deactivateStale(exchange, synced, startedAt);

        Instant finishedAt = Instant.now();
        String lastError = errors.isEmpty() ? null : String.join("; ", errors);

        statuses.put(exchange, new InstrumentSyncStatus(
                exchange, false, startedAt, finishedAt,
                upserted, deactivated, Set.copyOf(synced), Set.copyOf(failed), lastError));

        long tookMs = Duration.between(startedAt, finishedAt).toMillis();
        if (lastError == null) {
            log.info("Instrument sync {}: kinds={}, upserted={}, deactivated={}, took={}ms",
                    exchange, synced, upserted, deactivated, tookMs);

            // На время запуска шумим и об успехе: пока фича обкатывается, важнее видеть,
            // что синхронизация вообще происходит и сколько тянет. Когда доверие появится —
            // оставить только ветку с ошибкой, иначе канал превратится в фон.
            notifyService.broadcast("""
                    📚 Справочник инструментов обновлён

                    Биржа: %s
                    Типы: %s
                    Записано: %d
                    Снято с торгов: %d
                    Заняло: %.1f c
                    """.formatted(exchange, kindsForMessage(synced), upserted, deactivated, tookMs / 1000.0));
        } else {
            log.error("Instrument sync {}: kinds={}, failed={}, upserted={}, deactivated={}, took={}ms, err={}",
                    exchange, synced, failed, upserted, deactivated, tookMs, lastError);

            // Шумим в Telegram только на переходе «было хорошо → стало плохо» или когда
            // пользователь сам нажал кнопку и ждёт ответа. Иначе канал превращается в фон.
            if (wasHealthy || force) {
                notifyService.broadcast("""
                        ⚠️ Справочник инструментов обновлён частично

                        Биржа: %s
                        Загружено типов: %s
                        Не удалось: %s
                        Ошибка: %s
                        """.formatted(exchange, synced, failed, lastError));
            }
        }
    }

    /**
     * Короткая суть ошибки для сообщения и статуса.
     *
     * Spring вкладывает в текст DataAccessException весь SQL целиком — с ним сообщение
     * улетало в Telegram и возвращалось «message is too long», то есть уведомление
     * о сбое само не доходило. Полный стектрейс остаётся в логе.
     */
    private static String brief(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String firstLine = message.strip().lines().findFirst().orElse(message).strip();
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200) + "…";
    }

    /** Порядок enum-констант, а не HashSet: иначе список типов в сообщении скачет между прогонами. */
    private static String kindsForMessage(Set<InstrumentKind> kinds) {
        if (kinds.isEmpty()) return "—";
        return kinds.stream()
                .sorted(Comparator.comparingInt(InstrumentKind::ordinal))
                .map(InstrumentKind::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("—");
    }

    private boolean isFresh(ExchangeType exchange) {
        if (repo.countByExchangeAndActiveTrue(exchange) == 0) {
            return false;
        }
        return repo.findLastSyncedAt(exchange)
                .map(last -> last.isAfter(Instant.now().minus(FRESH_FOR)))
                .orElse(false);
    }

    private boolean wasHealthy(ExchangeType exchange) {
        InstrumentSyncStatus previous = statuses.get(exchange);
        return previous == null || previous.ok();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
