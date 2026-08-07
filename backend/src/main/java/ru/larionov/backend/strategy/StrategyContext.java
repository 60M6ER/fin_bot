package ru.larionov.backend.strategy;

import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;

import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Optional;
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

    <T> Optional<T> loadState(Class<T> type);

    void saveState(Object state);

    /** Неденежная отметка стратегии в общей книге операций. */
    void ledgerMarker(LedgerEntryType type, String note);

    Inventory inventory();

    BigDecimal realizedPnl();

    /**
     * Отмечает начало нового поколения сетки и закрывает предыдущее.
     *
     * Вызов идемпотентен: повторный запуск бота в том же поколении ничего не меняет
     * и возвращает пустой результат.
     *
     * @return итог закрытого поколения — то, что уходит в уведомление о перестановке
     */
    Optional<GridGenerationDto> rollGridGeneration(long generation, BigDecimal lowerPrice,
                                                   BigDecimal upperPrice, Integer levels,
                                                   String origin, Instant startedAt);

    /** Асинхронно и навсегда выключить runtime вместе с persisted desired-state. */
    void requestStop(String reason);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable t);

    /** Событие с явным типом — попадёт в журнал, консоль и, если нужно, в Telegram. */
    void event(BotEventType type, String message);

    void event(BotEventLevel level, BotEventType type, String message);
}
