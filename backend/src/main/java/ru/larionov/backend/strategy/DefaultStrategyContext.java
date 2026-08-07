package ru.larionov.backend.strategy;

import lombok.RequiredArgsConstructor;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.accounting.GridGenerationService;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.service.BotEventService;
import ru.larionov.backend.service.StrategyStateService;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Consumer;
import ru.larionov.backend.accounting.Inventory;

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
    private final StrategyStateService stateService;
    private final AccountingService accounting;
    private final GridGenerationService gridGenerations;
    private final Consumer<String> stopRequester;

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
    public <T> Optional<T> loadState(Class<T> type) {
        return stateService.read(botId, type);
    }

    @Override
    public void saveState(Object state) {
        stateService.write(botId, state);
    }

    @Override
    public void ledgerMarker(LedgerEntryType type, String note) {
        accounting.recordMarker(execution, type, note);
    }

    @Override
    public Inventory inventory() {
        return accounting.inventory(botId, execution.dryRun());
    }

    @Override
    public BigDecimal realizedPnl() {
        return accounting.summary(botId, execution.dryRun()).realizedPnl();
    }

    @Override
    public Optional<GridGenerationDto> rollGridGeneration(long generation, BigDecimal lowerPrice,
                                                          BigDecimal upperPrice, Integer levels,
                                                          String origin, Instant startedAt) {
        return gridGenerations.roll(execution, generation, lowerPrice, upperPrice,
                levels, origin, startedAt);
    }

    @Override
    public void requestStop(String reason) {
        stopRequester.accept(reason);
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

    @Override
    public void event(BotEventLevel level, BotEventType type, String message) {
        events.emit(botId, level, type, message, Map.of());
    }
}
