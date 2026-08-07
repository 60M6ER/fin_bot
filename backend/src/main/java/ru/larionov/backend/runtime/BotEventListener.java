package ru.larionov.backend.runtime;

import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.strategy.StrategyCommand;

/**
 * Приёмник событий бота. Все методы вызываются строго последовательно, на одном потоке —
 * это гарантирует {@link BotEventLoop}, поэтому реализациям не нужны блокировки.
 *
 * Отдельный от Strategy интерфейс: событийный цикл не должен знать о стратегиях,
 * а стратегия — о потоках и очередях.
 */
public interface BotEventListener {

    void onPrice(LastPrice price);

    void onOrderUpdate(OrderState state);

    void onTradingStatus(TradingStatusEvent event);

    /** Стрим переподключился: до любых новых действий нужно свериться с биржей. */
    void onStreamReconnect();

    /** Ручная команда оператора — единственный вход, у которого есть автор. */
    void onCommand(StrategyCommand command);

    /** Сторожевой тик: housekeeping и проверка, не залип ли стрим. */
    void onTick();
}
