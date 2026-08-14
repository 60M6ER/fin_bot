package ru.larionov.backend.strategy;

import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.accounting.DustBucket;
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

    /**
     * Цена, которую стратегия узнала НЕ из стрима.
     *
     * Стрим последней цены присылает событие только при сделке, поэтому на старте
     * его может не быть вовсе — и стратегия берёт цену REST-запросом (оценщик ATR
     * уже сходил за ней, чтобы построить диапазон). Без этого канала рыночная оценка
     * такую цену не видит: бот торгует, а «Рыночная стоимость» и P/L остаются пустыми
     * с подписью «цена из потока не получена». Ровно так и выглядели на боевом сервере
     * боты T-Invest 07.08.2026 — сетка стоит, сделки идут, оценки нет.
     *
     * Свежесть при этом не подделывается: время наблюдения передаётся честно, и цена,
     * полученная час назад, останется в интерфейсе часовой давности.
     */
    void observedPrice(BigDecimal price, Instant at);

    Inventory inventory();

    /** Накопленная пыль: непродаваемые хвосты закрытых циклов и их себестоимость. */
    DustBucket dust();

    /**
     * Сколько с каждого уровня уже переведено в пыль.
     *
     * Без этой суммы уровень выглядел бы занятым тем, что с него давно изъято:
     * заявки в журнале ордеров остаются навсегда, а изъятие повторялось бы
     * каждый проход, наращивая корзину из воздуха.
     */
    java.util.Map<Integer, java.math.BigDecimal> dustByLevel();

    /**
     * Переводит непродаваемый хвост уровня в корзину пыли.
     *
     * Себестоимость берётся из тех же партий, из которых хвост и остался, —
     * стратегия её не считает и передавать не может.
     */
    void recordDust(Integer gridLevel, BigDecimal quantity);

    /**
     * Разовый ремонт на старте: собирает пыль, осевшую до появления её учёта.
     * Идемпотентен, поэтому вызывается при каждом запуске без опаски.
     *
     * @return сколько всего переведено в пыль
     */
    BigDecimal sweepUntradableRemainders();

    BigDecimal realizedPnl();

    /**
     * Обеспечение счёта, на котором работает бот.
     *
     * Пусто, если площадка про маржу не отвечает или брокер промолчал. Пустота —
     * это «не знаем», а не «всё хорошо»: сторож на ней обязан молчать, а всякий,
     * кто на её основании РАЗРЕШАЕТ действие, обязан, наоборот, запретить.
     *
     * Показатели общие для всего счёта: соседние боты расходуют то же обеспечение.
     */
    Optional<ru.larionov.backend.exchange.api.model.account.MarginAttributes> marginAttributes();

    /**
     * Суточная ставка переноса непокрытой позиции с настроек подключения.
     *
     * Тариф принадлежит подключению, а не боту: копия его в конфигурации каждого
     * бота означала бы два расходящихся ответа на один вопрос. Ноль, если тариф
     * не задан, — лонговой сетке он всё равно не нужен.
     */
    BigDecimal carryDailyRate();

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
                                                   String origin, Instant startedAt,
                                                   String direction, boolean margin);

    /**
     * Открывает строку восстановительного эпизода в отчёте по поколениям.
     *
     * Со своим окном книги: деньги эпизода считаются тем же механизмом, что и деньги
     * поколения. Поколение при этом не закрывается — плечо и сетка живут одновременно.
     */
    void openRecoveryEpisode(long generation, UUID episodeId, String direction,
                             BigDecimal entryPrice, BigDecimal targetPrice,
                             BigDecimal multiplier, Instant startedAt);

    /** Закрывает строку эпизода: дальше её деньги уже не растут. */
    void closeRecoveryEpisode(UUID episodeId, Instant endedAt);

    /** Асинхронно и навсегда выключить runtime вместе с persisted desired-state. */
    void requestStop(String reason);

    void info(String message);

    void warn(String message);

    void error(String message, Throwable t);

    /** Событие с явным типом — попадёт в журнал, консоль и, если нужно, в Telegram. */
    void event(BotEventType type, String message);

    void event(BotEventLevel level, BotEventType type, String message);
}
