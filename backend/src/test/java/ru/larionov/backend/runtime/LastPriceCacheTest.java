package ru.larionov.backend.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LastPriceCacheTest {

    private final LastPriceCache cache = new LastPriceCache();

    @Test
    void returnsEmptyForABotThatNeverSawAPrice() {
        assertThat(cache.get(UUID.randomUUID())).isEmpty();
        assertThat(cache.get(null)).isEmpty();
    }

    @Test
    void keepsTheLatestPricePerBot() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Instant ts = Instant.parse("2026-01-08T12:00:00Z");

        cache.put(first, "uid", new BigDecimal("100"), ts);
        cache.put(first, "uid", new BigDecimal("101"), ts.plusSeconds(1));
        cache.put(second, "uid", new BigDecimal("55"), ts);

        // Ключ по боту, а не по инструменту: два бота на одном инструменте могут
        // смотреть на разные цены (стакан против цены сделки).
        assertThat(cache.get(first).orElseThrow().price()).isEqualByComparingTo("101");
        assertThat(cache.get(second).orElseThrow().price()).isEqualByComparingTo("55");
    }

    @Test
    void recordsWhenWeReceivedThePrice() {
        UUID botId = UUID.randomUUID();
        Instant before = Instant.now();

        cache.put(botId, "uid", new BigDecimal("100"), Instant.parse("2020-01-01T00:00:00Z"));

        var cached = cache.get(botId).orElseThrow();
        // exchangeTs может быть каким угодно, свежесть считается по receivedAt.
        assertThat(cached.exchangeTs()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(cached.receivedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void ignoresNullPrice() {
        UUID botId = UUID.randomUUID();
        cache.put(botId, "uid", null, Instant.now());
        assertThat(cache.get(botId)).isEmpty();
    }

    @Test
    void evictsOnlyTheRequestedBot() {
        UUID kept = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        cache.put(kept, "uid", new BigDecimal("1"), null);
        cache.put(removed, "uid", new BigDecimal("2"), null);

        cache.evict(removed);

        assertThat(cache.get(removed)).isEmpty();
        assertThat(cache.get(kept)).isPresent();
    }

    @Test
    void survivesConcurrentWritersFromStreamThreads() throws Exception {
        UUID botId = UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int seed = t;
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 500; i++) {
                        cache.put(botId, "uid", BigDecimal.valueOf(seed * 1000L + i), Instant.now());
                        cache.get(botId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(cache.get(botId)).isPresent();
    }
}
