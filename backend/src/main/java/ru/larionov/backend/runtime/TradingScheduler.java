package ru.larionov.backend.runtime;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Общий планировщик сторожевых тиков для всех ботов.
 *
 * Тик здесь — не движок стратегии (им стали события стримов), а housekeeping:
 * периодическая сверка и проверка, не залип ли стрим. Поэтому одного небольшого
 * пула хватает на всех.
 */
@Slf4j
@Component
public class TradingScheduler {

    private final AtomicInteger threadSeq = new AtomicInteger();

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "bot-tick-" + threadSeq.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    public ScheduledFuture<?> scheduleTick(Runnable task, long intervalSeconds) {
        // fixedDelay, а не fixedRate: если тик задержался, копить очередь тиков незачем.
        return executor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Exception e) {
                // Исключение из задачи иначе навсегда отменило бы расписание.
                log.error("Scheduled bot tick failed: {}", e.getMessage(), e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
