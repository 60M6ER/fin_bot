package ru.larionov.backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сводит желаемое состояние (is_active в БД — намерение пользователя)
 * с фактическим (запущенные хендлеры в памяти).
 *
 * Зачем: временный сбой — оборванная сеть, недоступный API брокера, перезапуск
 * приложения ночью — больше не гасит торговлю навсегда. desired-state при ошибке
 * не затирается, а супервизор повторяет попытку с нарастающей паузой, пока не поднимет.
 *
 * Порядок важен: сначала подключения, потом боты. Бот без живого подключения
 * стартовать не может, поэтому пытаться поднять его раньше бессмысленно.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeSupervisor {

    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final ExchangeConnectionRepository connectionRepo;
    private final BotRepository botRepo;
    private final ExchangeRuntimeService exchangeRuntimeService;
    private final BotRuntimeService botRuntimeService;

    private final ConcurrentHashMap<UUID, Backoff> backoffs = new ConcurrentHashMap<>();

    private volatile boolean shuttingDown = false;

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
    }

    @Scheduled(initialDelayString = "PT45S", fixedDelayString = "PT30S")
    public void reconcileDesiredState() {
        if (shuttingDown) {
            return;
        }

        try {
            reconcileConnections();
            reconcileBots();
        } catch (Exception e) {
            // Супервизор не имеет права умереть: если он упадёт, восстановление
            // перестанет работать молча.
            log.error("Supervisor pass failed: {}", e.getMessage(), e);
        }
    }

    private void reconcileConnections() {
        for (ExchangeConnectionEntity conn : connectionRepo.findAllByActiveTrueOrderByNameAsc()) {
            UUID id = conn.getId();

            if (exchangeRuntimeService.isRunning(id)) {
                backoffs.remove(id);
                continue;
            }

            if (!shouldAttempt(id)) {
                continue;
            }

            log.info("Supervisor: connection {} ({}) should be running but is not. Attempting start.",
                    conn.getName(), id);
            exchangeRuntimeService.start(id, false);
            recordOutcome(id, exchangeRuntimeService.isRunning(id));
        }
    }

    private void reconcileBots() {
        for (BotEntity bot : botRepo.findAllByActiveTrueOrderByNameAsc()) {
            UUID botId = bot.getId();

            if (botRuntimeService.isRunning(botId)) {
                backoffs.remove(botId);
                continue;
            }

            // Без живого подключения бот не поднимется — не тратим попытку и не копим backoff.
            if (!exchangeRuntimeService.isRunning(bot.getExchangeConnectionId())) {
                continue;
            }

            if (!shouldAttempt(botId)) {
                continue;
            }

            log.info("Supervisor: bot {} ({}) should be running but is not. Attempting start.",
                    bot.getName(), botId);
            botRuntimeService.start(botId, false);
            recordOutcome(botId, botRuntimeService.isRunning(botId));
        }
    }

    // ==============================
    // BACKOFF
    // ==============================

    private boolean shouldAttempt(UUID id) {
        Backoff b = backoffs.get(id);
        return b == null || !Instant.now().isBefore(b.nextAttemptAt());
    }

    private void recordOutcome(UUID id, boolean success) {
        if (success) {
            backoffs.remove(id);
            return;
        }

        Backoff previous = backoffs.get(id);
        int failures = previous == null ? 1 : previous.failures() + 1;

        // 30s, 60s, 120s, ... но не дольше MAX_BACKOFF
        Duration delay = BASE_BACKOFF.multipliedBy(1L << Math.min(failures - 1, 8));
        if (delay.compareTo(MAX_BACKOFF) > 0) {
            delay = MAX_BACKOFF;
        }

        backoffs.put(id, new Backoff(failures, Instant.now().plus(delay)));
        log.warn("Supervisor: start of {} failed ({} consecutive). Next attempt in {}s.",
                id, failures, delay.toSeconds());
    }

    private record Backoff(int failures, Instant nextAttemptAt) {}
}
