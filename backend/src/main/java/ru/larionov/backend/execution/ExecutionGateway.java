package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Единственный путь стратегии к бирже. Напрямую в OrdersApi стратегия не ходит —
 * иначе она могла бы обойти лимиты и не попасть в журнал.
 *
 * Гейтвей отвечает за три вещи, которые стратегия знать не должна:
 * идемпотентность постановки, ведение журнала и проверку лимитов.
 */
public interface ExecutionGateway {

    /**
     * Сверка с биржей. Медленный путь, но именно он делает состояние достоверным.
     *
     * Вызывается при старте бота, при переподключении любого стрима и по сторожевому
     * таймеру. Разрешает «повисшие» PENDING, подбирает ордера, о которых журнал не знает,
     * и сравнивает позицию журнала с фактической.
     */
    ReconcileResult reconcile(BotExecutionContext ctx);

    /**
     * Постановка лимитного ордера.
     *
     * Порядок критичен: лимиты → запись PENDING в журнал → сетевой вызов → обновление.
     * Если вызов не ответит, запись останется PENDING и её судьбу выяснит сверка;
     * повторная постановка с тем же clientOrderId дубля не создаст.
     *
     * @throws RiskRejectedException если ордер запрещён лимитами
     */
    BotOrderView placeLimit(BotExecutionContext ctx, PlaceIntent intent);

    void cancel(BotExecutionContext ctx, UUID botOrderId);

    /** Снять все живые заявки бота — например, при выходе цены из диапазона. */
    int cancelAll(BotExecutionContext ctx);

    List<BotOrderView> openOrders(UUID botId);

    /**
     * Быстрый путь: событие из ордерного стрима. Сопоставляется с журналом по
     * clientOrderId. Событие по чужому идентификатору (ручная сделка в приложении
     * брокера) — не ошибка, просто не наше.
     *
     * @return обновлённый ордер, если событие относилось к нам
     */
    Optional<BotOrderView> applyOrderEvent(BotExecutionContext ctx, OrderState fromStream);

    /**
     * Бумажный режим: цена двигает симуляцию исполнения. В живом режиме — no-op,
     * там исполнение приходит от биржи.
     *
     * @return ордера, «исполнившиеся» на этой цене
     */
    default List<BotOrderView> onPrice(BotExecutionContext ctx, LastPrice price) {
        return List.of();
    }

    /** Живой режим или бумажный — стратегии полезно знать для сообщений. */
    boolean isDryRun();
}
