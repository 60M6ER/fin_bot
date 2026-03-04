package ru.larionov.backend.service;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;

import java.util.UUID;

public interface ExchangeHandler extends AutoCloseable {
    UUID connectionId();
    ExchangeType exchangeType();

    void start();     // поднимаем SDK, стримы, подписки
    void stop();      // мягкая остановка (идемпотентно)
    void test();      // health-check: бросает исключение при проблеме

    ExchangeClient client();

    @Override default void close() { stop(); }
}

