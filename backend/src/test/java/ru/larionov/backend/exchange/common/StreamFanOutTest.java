package ru.larionov.backend.exchange.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Раздача событий стрима: подписка одна, получателей сколько угодно.
 *
 * Главный тест здесь — {@link #restartedSubscriberReceivesEventsWithoutNewBrokerSubscription()}.
 * Он воспроизводит инцидент 14.08.2026: бот перезапустили, инструмент у брокера уже
 * подписан, и SDK T-Invest в этом случае молча не регистрирует нового слушателя.
 * Перезапущенная сетка MAGN простояла под нижней границей, не увидев пробоя, — при
 * этом цена в её карточке выглядела свежей, потому что кэш наполнял обработчик прошлой
 * жизни бота.
 */
class StreamFanOutTest {

    private static final String UID = "7132b1c9-magn";

    @Test
    @DisplayName("подписка у брокера заводится один раз на инструмент")
    void brokerSubscriptionHappensOnlyForNewInstruments() {
        StreamFanOut<String> fanOut = new StreamFanOut<>();
        List<Set<String>> subscribed = new ArrayList<>();

        fanOut.register(Set.of(UID), event -> { }, subscribed::add);
        fanOut.register(Set.of(UID), event -> { }, subscribed::add);

        assertThat(subscribed)
                .as("второй получатель того же инструмента новой подписки не требует")
                .containsExactly(Set.of(UID));
    }

    @Test
    @DisplayName("событие получают все текущие подписчики")
    void everyRegisteredHandlerReceivesTheEvent() {
        StreamFanOut<String> fanOut = new StreamFanOut<>();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();

        fanOut.register(Set.of(UID), first::add, __ -> { });
        fanOut.register(Set.of(UID), second::add, __ -> { });
        deliver(fanOut, "20.38");

        assertThat(first).containsExactly("20.38");
        assertThat(second).containsExactly("20.38");
    }

    /**
     * Тот самый случай: бот остановлен и запущен заново.
     *
     * Обработчик прежнего запуска обязан уйти — он замкнут на закрытый цикл событий.
     * Обработчик нового обязан получать данные, хотя подписка у брокера осталась
     * прежней и заводить её заново никто не будет.
     */
    @Test
    @DisplayName("перезапущенный подписчик получает события, хотя подписка у брокера прежняя")
    void restartedSubscriberReceivesEventsWithoutNewBrokerSubscription() {
        StreamFanOut<String> fanOut = new StreamFanOut<>();
        AtomicInteger brokerSubscriptions = new AtomicInteger();
        Consumer<Set<String>> subscribe = __ -> brokerSubscriptions.incrementAndGet();

        List<String> beforeRestart = new ArrayList<>();
        Runnable unsubscribe = fanOut.register(Set.of(UID), beforeRestart::add, subscribe);

        // Остановка бота: свой обработчик снимаем, подписку у брокера — нет.
        unsubscribe.run();

        List<String> afterRestart = new ArrayList<>();
        fanOut.register(Set.of(UID), afterRestart::add, subscribe);
        deliver(fanOut, "20.38");

        assertThat(afterRestart)
                .as("новый запуск бота обязан видеть цену")
                .containsExactly("20.38");
        assertThat(beforeRestart)
                .as("прежний обработчик снят и данных больше не получает")
                .isEmpty();
        assertThat(brokerSubscriptions)
                .as("подписку у брокера не трогаем: инструмент мог слушать соседний бот")
                .hasValue(1);
    }

    @Test
    @DisplayName("снятие одного обработчика не задевает остальных")
    void unsubscribeRemovesOnlyItsOwnHandler() {
        StreamFanOut<String> fanOut = new StreamFanOut<>();
        List<String> mine = new ArrayList<>();
        List<String> neighbour = new ArrayList<>();

        Runnable unsubscribe = fanOut.register(Set.of(UID), mine::add, __ -> { });
        fanOut.register(Set.of(UID), neighbour::add, __ -> { });

        unsubscribe.run();
        deliver(fanOut, "20.38");

        assertThat(mine).isEmpty();
        assertThat(neighbour)
                .as("соседний бот по тому же инструменту продолжает получать данные")
                .containsExactly("20.38");
    }

    @Test
    @DisplayName("получателя ищем и по uid, и по figi")
    void listenersAreFoundByEitherIdentifier() {
        StreamFanOut<String> fanOut = new StreamFanOut<>();
        List<String> received = new ArrayList<>();
        fanOut.register(Set.of("BBG004S68507"), received::add, __ -> { });

        // Событие пришло с обоими идентификаторами, а подписан инструмент по figi.
        for (Consumer<String> handler : fanOut.listeners(UID, "BBG004S68507")) {
            handler.accept("20.38");
        }

        assertThat(received).containsExactly("20.38");
    }

    private void deliver(StreamFanOut<String> fanOut, String event) {
        for (Consumer<String> handler : fanOut.listeners(UID, null)) {
            handler.accept(event);
        }
    }
}
