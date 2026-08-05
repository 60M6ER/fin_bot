package ru.larionov.backend.strategy;

import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.ReconcileResult;

import java.util.Optional;

/**
 * Стратегия — реакция на события, а не цикл опроса.
 *
 * Все методы вызываются строго последовательно на одном потоке (это обеспечивает
 * BotEventLoop), поэтому блокировки внутри стратегии не нужны.
 */
public interface Strategy {

    void onStart(StrategyContext ctx);

    /** Старт с уже выполненной сверкой, чтобы стратегия не гадала о позиции после рестарта. */
    default void onStart(StrategyContext ctx, ReconcileResult initialState) {
        onStart(ctx);
    }

    void onStop();

    /** Изменение цены. Источник — стрим последних цен или стакан. */
    default void onPrice(LastPrice price) {
    }

    /**
     * Изменение состояния нашего ордера — уже сопоставленное с журналом.
     *
     * Передаём именно {@link BotOrderView}, а не сырое состояние с биржи: в нём есть
     * уровень сетки и наш идентификатор, без которых стратегия не сможет понять,
     * какую встречную заявку выставить.
     */
    default void onOrderUpdate(BotOrderView order) {
    }

    /**
     * Смена торгового статуса инструмента.
     *
     * Для T-Invest это главный триггер жизненного цикла: GTC в протоколе нет,
     * лимитные ордера умирают в конце сессии, поэтому открытие торгов означает
     * «сверься и расставь заявки заново».
     */
    default void onTradingStatus(TradingStatusEvent event) {
    }

    /** Стрим переподключился: до любых новых действий нужно свериться с биржей. */
    default void onStreamReconnect() {
    }

    /**
     * Итог сверки с биржей — вызывается ПОСЛЕ каждой сверки, кто бы её ни инициировал.
     *
     * Без этого метода стратегия узнавала о расхождении позиции только из тех сверок,
     * которые запускала сама. Сверки при старте бота и после реконнекта стрима делает
     * хендлер, их результат стратегия не видела — и продолжала торговать по журналу,
     * который сверка только что признала расходящимся с биржей. Именно в это окно
     * бот выставил продажи на позицию, которой на бирже не было.
     */
    default void onReconcile(ReconcileResult result) {
    }

    /**
     * Сторожевой тик. Не движок стратегии, а housekeeping: периодическая сверка
     * и проверка, не залип ли стрим.
     */
    default void onTick() {
    }

    default Optional<StrategySnapshot> snapshot() {
        return Optional.empty();
    }
}
