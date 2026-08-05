package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import ru.larionov.backend.dto.SystemInfoDto;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Перезапуск приложения по кнопке.
 *
 * Механика: корректно останавливаем торговлю, гасим контекст и завершаем процесс.
 * Контейнер поднимает нас обратно сам — в docker-compose у сервиса стоит
 * {@code restart: unless-stopped}, который срабатывает на ЛЮБОЙ код выхода,
 * включая нулевой.
 *
 * Почему не actuator {@code /shutdown}: он не даёт ни упорядоченного выключения,
 * ни ответа ДО выхода, ни сторожа на случай зависшего брокера, ни ручки, по которой
 * фронтенд поймёт, что поднялся уже новый процесс. И называется «выключить»,
 * а не «перезапустить».
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemRestartService {

    /** Пауза перед выключением: ответ должен успеть уйти в сеть. */
    private static final long RESPONSE_GRACE_MS = 500;

    /**
     * Сторож на случай, если снятие заявок повисло на недоступном брокере.
     *
     * {@code timeout-per-shutdown-phase} ограничивает фазы SmartLifecycle, но НЕ
     * @PreDestroy, поэтому без этого сторожа процесс мог бы не умереть вовсе.
     * 40 секунд — чуть меньше, чем stop_grace_period контейнера (45 с), чтобы
     * ручной docker stop и кнопка вели себя одинаково.
     */
    private static final long WATCHDOG_MS = 40_000;

    private final ApplicationContext context;
    private final TradingShutdownCoordinator shutdownCoordinator;

    @Value("${app.restart.enabled:true}")
    private boolean restartEnabled;

    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public SystemInfoDto info() {
        return new SystemInfoDto(instanceId, startedAt, restartEnabled, restarting.get());
    }

    public SystemInfoDto requestRestart() {
        if (!restartEnabled) {
            throw new IllegalStateException(
                    "Перезапуск отключён настройкой app.restart.enabled. Вне контейнера "
                            + "поднимать приложение обратно было бы некому.");
        }
        // Идемпотентно: двойной клик не запускает две последовательности выключения.
        if (!restarting.compareAndSet(false, true)) {
            log.info("Перезапуск уже идёт — повторный запрос проигнорирован");
            return info();
        }

        log.warn("Запрошен перезапуск приложения (instance {})", instanceId);
        Thread.ofPlatform().name("app-restart").daemon(false).start(this::performRestart);
        return info();
    }

    private void performRestart() {
        sleepQuietly(RESPONSE_GRACE_MS);

        Thread.ofPlatform().name("app-restart-watchdog").daemon(true).start(() -> {
            sleepQuietly(WATCHDOG_MS);
            log.error("Корректное выключение не уложилось в {} мс — завершаю процесс принудительно",
                    WATCHDOG_MS);
            Runtime.getRuntime().halt(0);
        });

        // Тот же порядок, что и при обычной остановке контейнера, — одной реализацией.
        shutdownCoordinator.shutdownInOrder();

        int code = SpringApplication.exit(context, () -> 0);
        log.warn("Контекст остановлен, выхожу с кодом {} — контейнер поднимет приложение заново", code);
        System.exit(code);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
