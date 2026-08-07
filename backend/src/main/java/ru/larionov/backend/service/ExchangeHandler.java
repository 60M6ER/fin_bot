package ru.larionov.backend.service;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

import java.util.UUID;

public interface ExchangeHandler extends AutoCloseable {
    UUID connectionId();
    ExchangeType exchangeType();

    void start();     // поднимаем SDK, стримы, подписки
    void stop();      // мягкая остановка (идемпотентно)
    void test();      // health-check: бросает исключение при проблеме

    ExchangeClient client();

    /**
     * Работает ли подключение в песочнице.
     *
     * В интерфейсе, а не через instanceof на конкретный адаптер: синхронизация
     * справочника предпочитает боевое подключение песочному, и это решение не должно
     * знать, какие адаптеры вообще существуют. Биржи без песочницы отвечают false.
     */
    default boolean sandbox() { return false; }

    /** Счёт, однозначно подтверждённый health-check подключения. */
    AccountId tradingAccountId();

    /**
     * Живость стримов. Читается без побочных эффектов: опрос статуса не должен
     * сам поднимать соединения. Биржи без стримов отдают «отключено».
     */
    default StreamHealth marketDataStreamHealth() { return StreamHealth.disconnected(); }

    default StreamHealth ordersStreamHealth() { return StreamHealth.disconnected(); }

    @Override default void close() { stop(); }
}
