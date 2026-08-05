package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

import java.util.function.Consumer;

/**
 * Поток изменений состояния ордеров.
 *
 * Ключевое свойство: событие приходит с нашим clientOrderId, поэтому сопоставляется
 * с записью в журнале напрямую и поллинг статусов не нужен.
 *
 * Как и с рыночными данными, стрим не отменяет сверку: события, потерянные во время
 * разрыва, восстанавливаются только REST-запросом.
 */
public interface OrdersStreamService extends AutoCloseable {

    void subscribeOrderStates(AccountId accountId, Consumer<OrderState> handler);

    void onReconnect(Runnable handler);

    StreamHealth health();

    @Override
    void close();
}
