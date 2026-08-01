package ru.larionov.backend.strategy;

import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;

import java.time.Clock;
import java.util.UUID;

/**
 * Всё, что стратегии нужно от окружения.
 *
 * Обратите внимание, чего здесь нет: прямого доступа к OrdersApi. Ордера ставятся
 * только через {@link #gateway()}, потому что там живут лимиты, журнал и
 * идемпотентность — обойти их стратегия не должна.
 */
public interface StrategyContext {

    UUID botId();

    /** Параметры бота для гейтвея: счёт, инструмент, лимиты, режим. */
    BotExecutionContext execution();

    ExecutionGateway gateway();

    /** Лотность и шаг цены инструмента: без них нельзя корректно округлить цены. */
    TradingConstraints constraints();

    /** Прямой доступ к бирже для чтения: свечи, стакан, календарь. */
    ExchangeClient exchange();

    Clock clock();

    void info(String message);

    void warn(String message);

    void error(String message, Throwable t);

    /** Событие с явным типом — попадёт в журнал, консоль и, если нужно, в Telegram. */
    void event(BotEventType type, String message);
}
