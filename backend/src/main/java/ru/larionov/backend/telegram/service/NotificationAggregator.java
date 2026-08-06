package ru.larionov.backend.telegram.service;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Склеивает пачку уведомлений в одно сообщение.
 *
 * <h3>Зачем</h3>
 * Раньше поток событий резался по лимиту частоты: лишние уведомления просто
 * выбрасывались. Это ровно та плата, которую платить не хочется — терялись как раз
 * события бурного момента, когда смотреть интереснее всего.
 *
 * Здесь другой размен: ничего не выбрасываем, а ждём, пока поток стихнет, и отдаём
 * всё разом. Телефон получает одно сообщение вместо десятка, но содержимое целое.
 *
 * <h3>Как считается пауза</h3>
 * После первого сообщения ждём {@link #QUIET_PERIOD}. Каждое новое сообщение сбрасывает
 * это ожидание — то есть отправка происходит, когда поток затих. Чтобы непрерывный
 * поток не откладывал отправку бесконечно, есть жёсткий потолок {@link #MAX_WAIT}
 * от ПЕРВОГО сообщения пачки.
 *
 * <h3>Чего здесь намеренно нет</h3>
 * Ошибки сюда не попадают: их отправляют отдельным сообщением сразу
 * ({@code broadcastIsolated}). Слипшаяся с пятью housekeeping-строками ошибка
 * теряется, а её как раз и нужно видеть.
 */
@Slf4j
public class NotificationAggregator {

    /** Сколько ждать тишины, прежде чем отправить накопленное. */
    static final Duration QUIET_PERIOD = Duration.ofSeconds(1);

    /** Потолок ожидания от первого сообщения пачки: поток может не стихать долго. */
    static final Duration MAX_WAIT = Duration.ofSeconds(5);

    /**
     * Telegram режет сообщения длиннее 4096 символов. Берём с запасом и отправляем
     * накопленное частями, а не теряем хвост.
     */
    private static final int MAX_MESSAGE_CHARS = 3500;

    private final Consumer<String> sink;
    private final ScheduledExecutorService scheduler;

    private final Object lock = new Object();
    private final List<String> pending = new ArrayList<>();
    private ScheduledFuture<?> scheduled;
    private Instant batchStartedAt;

    public NotificationAggregator(Consumer<String> sink) {
        this.sink = sink;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tg-notify-aggregator");
            t.setDaemon(true);
            return t;
        });
    }

    public void submit(String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (pending.isEmpty()) {
                batchStartedAt = Instant.now();
            }
            pending.add(block.strip());
            reschedule();
        }
    }

    /** Отправить накопленное немедленно. Вызывается при остановке приложения. */
    public void flushNow() {
        flush();
    }

    public void shutdown() {
        synchronized (lock) {
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
        }
        // Копим ради экономии сообщений, а не ради их потери: остаток отдаём как есть.
        flush();
        scheduler.shutdownNow();
    }

    /** Вызывается под {@link #lock}. */
    private void reschedule() {
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        Instant now = Instant.now();
        Instant target = now.plus(QUIET_PERIOD);
        Instant deadline = batchStartedAt.plus(MAX_WAIT);
        if (target.isAfter(deadline)) {
            target = deadline;
        }
        long delayMs = Math.max(0, Duration.between(now, target).toMillis());
        scheduled = scheduler.schedule(this::flush, delayMs, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        List<String> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(pending);
            pending.clear();
            scheduled = null;
            batchStartedAt = null;
        }

        try {
            for (String message : split(batch)) {
                sink.accept(message);
            }
        } catch (Exception e) {
            log.warn("Не удалось отправить накопленные уведомления: {}", e.getMessage(), e);
        }
    }

    /** Блоки разделяются пустой строкой; слишком длинная пачка бьётся на сообщения. */
    private static List<String> split(List<String> blocks) {
        List<String> messages = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (!current.isEmpty() && current.length() + block.length() + 2 > MAX_MESSAGE_CHARS) {
                messages.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(block);
        }
        if (!current.isEmpty()) {
            messages.add(current.toString());
        }
        return messages;
    }
}
