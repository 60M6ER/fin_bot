package ru.larionov.backend.strategy;

import lombok.RequiredArgsConstructor;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.service.BotEventService;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Реализация контекста стратегии. До этого этапа их не существовало ни одной —
 * интерфейс был объявлен, но не реализован, из-за чего весь слой стратегий
 * оставался недостижимым.
 */
@RequiredArgsConstructor
public class DefaultStrategyContext implements StrategyContext {

    private final UUID botId;
    private final BotExecutionContext execution;
    private final ExecutionGateway gateway;
    private final TradingConstraints constraints;
    /** Клиент через поставщика: подключение может быть переподнято под ботом. */
    private final Supplier<ExchangeClient> clientSupplier;
    private final BotEventService events;
    private final Clock clock;

    @Override
    public UUID botId() {
        return botId;
    }

    @Override
    public BotExecutionContext execution() {
        return execution;
    }

    @Override
    public ExecutionGateway gateway() {
        return gateway;
    }

    @Override
    public TradingConstraints constraints() {
        return constraints;
    }

    @Override
    public ExchangeClient exchange() {
        return clientSupplier.get();
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    public void info(String message) {
        events.emit(botId, BotEventLevel.INFO, BotEventType.HOUSEKEEPING, message, Map.of());
    }

    @Override
    public void warn(String message) {
        events.emit(botId, BotEventLevel.WARN, BotEventType.HOUSEKEEPING, message, Map.of());
    }

    @Override
    public void error(String message, Throwable t) {
        String text = t == null ? message : message + ": " + t.getMessage();
        events.emit(botId, BotEventLevel.ERROR, BotEventType.ERROR, text, Map.of());
    }

    @Override
    public void event(BotEventType type, String message) {
        BotEventLevel level = switch (type) {
            case ERROR -> BotEventLevel.ERROR;
            case RANGE_EXIT, RISK_BLOCKED, STREAM_RECONNECTED, ORDER_REJECTED -> BotEventLevel.WARN;
            default -> BotEventLevel.INFO;
        };
        events.emit(botId, level, type, message, Map.of());
    }
}
