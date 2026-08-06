package ru.larionov.backend.telegram.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Склейка уведомлений во времени.
 *
 * Здесь проверяется ровно тот размен, ради которого лимит частоты был убран:
 * поток не режется, а ждёт тишины — но ждать бесконечно не может.
 */
class NotificationAggregatorTest {

    private List<String> sent;
    private NotificationAggregator aggregator;

    @BeforeEach
    void setUp() {
        sent = new CopyOnWriteArrayList<>();
        aggregator = new NotificationAggregator(sent::add);
    }

    @AfterEach
    void tearDown() {
        aggregator.shutdown();
    }

    /** Ждём условие опросом: отдельная зависимость ради этого не нужна. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Условие не выполнилось за 3 секунды");
    }

    @Test
    void singleMessageGoesOutAfterTheQuietPeriod() {
        aggregator.submit("первое");

        // До паузы тишины ничего не улетает.
        assertThat(sent).isEmpty();

        awaitUntil(() -> !sent.isEmpty());
        assertThat(sent).containsExactly("первое");
    }

    @Test
    void burstArrivesAsOneMessageSeparatedByBlankLines() {
        aggregator.submit("раз");
        aggregator.submit("два");
        aggregator.submit("три");

        awaitUntil(() -> !sent.isEmpty());

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).isEqualTo("раз\n\nдва\n\nтри");
    }

    @Test
    void eachNewMessageResetsTheQuietPeriod() throws Exception {
        aggregator.submit("первое");
        // Не даём потоку затихнуть: подсыпаем чаще, чем длится пауза тишины.
        for (int i = 0; i < 4; i++) {
            Thread.sleep(600);
            aggregator.submit("ещё " + i);
            assertThat(sent).as("пока поток идёт, отправки быть не должно").isEmpty();
        }

        awaitUntil(() -> !sent.isEmpty());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("первое").contains("ещё 3");
    }

    /**
     * Главная защита от обратной стороны склейки: непрерывный поток не должен
     * откладывать отправку бесконечно.
     */
    @Test
    void neverWaitsLongerThanTheHardCeiling() throws Exception {
        long startedAt = System.nanoTime();
        aggregator.submit("первое");

        // Сыплем без остановки заведомо дольше потолка ожидания.
        for (int i = 0; i < 16 && sent.isEmpty(); i++) {
            Thread.sleep(500);
            aggregator.submit("ещё " + i);
        }

        awaitUntil(() -> !sent.isEmpty());

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        assertThat(elapsedMs)
                .as("отправка обязана случиться не позже потолка от первого сообщения")
                .isLessThan(NotificationAggregator.MAX_WAIT.toMillis() + 1500);
        assertThat(sent.get(0)).startsWith("первое");
    }

    @Test
    void nextBatchStartsCleanAfterAFlush() {
        aggregator.submit("пачка 1");
        awaitUntil(() -> sent.size() == 1);

        aggregator.submit("пачка 2");
        awaitUntil(() -> sent.size() == 2);

        assertThat(sent).containsExactly("пачка 1", "пачка 2");
    }

    @Test
    void blankSubmissionsAreIgnored() {
        aggregator.submit(null);
        aggregator.submit("   ");
        aggregator.submit("настоящее");

        awaitUntil(() -> !sent.isEmpty());
        assertThat(sent).containsExactly("настоящее");
    }

    /** Копим ради экономии сообщений, а не ради их потери. */
    @Test
    void shutdownFlushesWhatWasStillPending() {
        aggregator.submit("не потеряй меня");
        aggregator.shutdown();

        assertThat(sent).containsExactly("не потеряй меня");
    }

    @Test
    void oversizedBatchIsSplitInsteadOfLosingTheTail() {
        String block = "x".repeat(1200);
        for (int i = 0; i < 5; i++) {
            aggregator.submit(block + " #" + i);
        }

        awaitUntil(() -> !sent.isEmpty());

        assertThat(sent.size()).isGreaterThan(1);
        assertThat(String.join("", sent)).contains("#0").contains("#4");
        assertThat(sent).allSatisfy(m -> assertThat(m.length()).isLessThan(4096));
    }
}
