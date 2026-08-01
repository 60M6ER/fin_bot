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

    void subscribeLastPrice(Set<InstrumentId> instruments, Consumer<LastPrice> handler);

    void subscribeOrderBook(Set<InstrumentId> instruments, int depth, Consumer<OrderBook> handler);

    void subscribeTradingStatus(Set<InstrumentId> instruments, Consumer<TradingStatusEvent> handler);

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
