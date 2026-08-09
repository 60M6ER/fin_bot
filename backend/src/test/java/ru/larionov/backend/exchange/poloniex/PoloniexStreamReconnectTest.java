package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.event.spot.TickerEvent;
import com.poloniex.api.client.spot.ws.PoloApiCallback;
import com.poloniex.api.client.spot.ws.spot.SpotPoloPublicWebsocketClient;
import okhttp3.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Стрим Poloniex обязан подниматься сам.
 *
 * 09.08.2026 вебсокеты оборвались, и оба бота простояли до ручного перезапуска
 * подключения: адаптер об обрыве только сообщал, но подписку не восстанавливал,
 * а снаружи подключение выглядело рабочим — REST-вызовы у него живы.
 */
class PoloniexStreamReconnectTest {

    private SpotPoloPublicWebsocketClient client;
    private PoloniexMarketDataStreamService service;
    private final List<PoloApiCallback<TickerEvent>> callbacks = new CopyOnWriteArrayList<>();
    private final AtomicInteger subscribeCalls = new AtomicInteger();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(SpotPoloPublicWebsocketClient.class);
        when(client.onTickerEvent(anyList(), any())).thenAnswer(invocation -> {
            callbacks.add((PoloApiCallback<TickerEvent>) invocation.getArgument(1));
            subscribeCalls.incrementAndGet();
            return mock(WebSocket.class);
        });
        service = new PoloniexMarketDataStreamService(client);
    }

    @AfterEach
    void tearDown() {
        service.close();
    }

    @Test
    void aBrokenSocketIsResubscribedOnItsOwn() {
        service.subscribeLastPrice(Set.of(new InstrumentId("POLONIEX:SOL_USDT", null)), price -> { });
        assertThat(subscribeCalls.get()).isEqualTo(1);

        callbacks.get(0).onFailure(new RuntimeException("socket closed"));

        assertThat(service.health().connected())
                .as("пока подписка не восстановлена, стрим честно считается мёртвым")
                .isFalse();

        await(() -> subscribeCalls.get() >= 2);
        assertThat(service.health().connected())
                .as("после восстановления стрим снова живой")
                .isTrue();
    }

    /**
     * Сверка нужна ПОСЛЕ восстановления, а не в момент обрыва: за время разрыва
     * события потерялись безвозвратно, и сверяться есть с чем только когда связь есть.
     */
    @Test
    void subscribersAreNotifiedAfterTheStreamIsBackNotWhenItBreaks() {
        AtomicInteger notified = new AtomicInteger();
        service.onReconnect(notified::incrementAndGet);
        service.subscribeLastPrice(Set.of(new InstrumentId("POLONIEX:SOL_USDT", null)), price -> { });

        callbacks.get(0).onFailure(new RuntimeException("socket closed"));
        assertThat(notified.get()).as("в момент обрыва сверяться не с чем").isZero();

        await(() -> notified.get() >= 1);
    }

    /** Снятую подписку восстанавливать нечего: иначе поток переживёт своего владельца. */
    @Test
    void aCancelledSubscriptionIsNotBroughtBack() {
        service.subscribeLastPrice(Set.of(new InstrumentId("POLONIEX:SOL_USDT", null)), price -> { });
        PoloApiCallback<TickerEvent> callback = callbacks.get(0);

        service.unsubscribeAll();
        callback.onFailure(new RuntimeException("socket closed"));

        sleep(Duration.ofMillis(1500));
        assertThat(subscribeCalls.get())
                .as("после снятия подписки новых подключений быть не должно")
                .isEqualTo(1);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(5);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(50));
        }
        throw new AssertionError("Не дождались восстановления подписки за 5 секунд");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
