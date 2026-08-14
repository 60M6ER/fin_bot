package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Потоковые рыночные данные. Контракт вендор-нейтральный: Poloniex позже встанет
 * на это же место без изменений в ядре.
 *
 * Обработчики вызываются на потоках адаптера, параллельно и из разных подписок.
 * Сериализацию обеспечивает вызывающая сторона (BotEventLoop), а не адаптер.
 */
public interface MarketDataStreamService extends AutoCloseable {

    /**
     * Подписка возвращает СВОЮ отписку, и это не удобство.
     *
     * Подписки живут на уровне подключения и переживают остановку бота — иначе
     * остановка одного оборвала бы поток соседям по тому же инструменту. Но обработчик
     * принадлежит конкретному запуску бота: он замкнут на его цикл событий, и после
     * остановки обязан уйти. Без этого перезапущенный бот получал бы данные в мёртвый
     * цикл, а живой — не получал вовсе.
     *
     * @return снятие ИМЕННО ЭТОГО обработчика; на подписку у брокера не влияет
     */
    Runnable subscribeLastPrice(Set<InstrumentId> instruments, Consumer<LastPrice> handler);

    Runnable subscribeOrderBook(Set<InstrumentId> instruments, int depth, Consumer<OrderBook> handler);

    Runnable subscribeTradingStatus(Set<InstrumentId> instruments, Consumer<TradingStatusEvent> handler);

    void unsubscribeAll();

    /**
     * Хук на (пере)подключение стрима — обязателен для корректности, а не удобство.
     * За время разрыва события теряются безвозвратно, поэтому после реконнекта
     * состояние нужно пересинхронизировать REST-сверкой, прежде чем действовать дальше.
     */
    void onReconnect(Runnable handler);

    StreamHealth health();

    @Override
    void close();
}
