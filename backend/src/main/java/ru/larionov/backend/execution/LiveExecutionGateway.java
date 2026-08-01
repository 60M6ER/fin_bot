package ru.larionov.backend.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.enums.TimeInForce;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.BotEventService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Живой гейтвей: реальные ордера на бирже.
 *
 * Ключевое свойство — идемпотентность постановки. clientOrderId генерируется и
 * сохраняется в журнал ДО сетевого вызова, поэтому оборванный вызов не приводит
 * ни к потере ордера, ни к дублю: T-Invest дедуплицирует по этому идентификатору,
 * а сверка выясняет судьбу записи запросом состояния по нему же.
 */
@Slf4j
@RequiredArgsConstructor
public class LiveExecutionGateway implements ExecutionGateway {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN);

    private final BotOrderRepository orderRepo;
    private final RiskGuard riskGuard;
    private final BotEventService events;
    /** Клиент берём через поставщика: подключение может быть переподнято под нами. */
    private final Supplier<ExchangeClient> clientSupplier;

    @Override
    public boolean isDryRun() {
        return false;
    }

    // ==============================
    // PLACE
    // ==============================

    @Override
    public BotOrderView placeLimit(BotExecutionContext ctx, PlaceIntent intent) {
        riskGuard.check(ctx, intent);

        // Шаг 1: фиксируем намерение в журнале ДО обращения к бирже.
        // UUID даёт ровно 36 символов — предел поля orderId у T-Invest.
        String clientOrderId = UUID.randomUUID().toString();
        BotOrderEntity entity = orderRepo.save(BotOrderEntity.builder()
                .botId(ctx.botId())
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(clientOrderId)
                .side(intent.side())
                .status(OrderStatus.PENDING)
                .gridLevel(intent.gridLevel())
                .requestedLots(intent.lots())
                .executedLots(0)
                .limitPrice(intent.limitPrice())
                .dryRun(false)
                .build());

        // Шаг 2: сетевой вызов.
        try {
            OrderResponse response = orders().placeLimit(new OrderRequest(
                    ctx.accountId(),
                    ctx.instrumentId(),
                    new ClientOrderId(clientOrderId),
                    intent.side(),
                    BigDecimal.valueOf(intent.lots()),
                    intent.limitPrice(),
                    TimeInForce.GTC // у T-Invest всё равно DAY: GTC в протоколе нет
            ));

            applyState(entity, response.state());
            entity.setExchangeOrderId(response.orderId() == null ? null : response.orderId().value());
            if (entity.getStatus() == OrderStatus.PENDING) {
                entity.setStatus(OrderStatus.NEW);
            }
            entity.setLastError(null);
            orderRepo.save(entity);

            riskGuard.recordPlacement(ctx.botId());
            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_PLACED,
                    "%s %d лот(ов) по %s".formatted(intent.side(), intent.lots(), intent.limitPrice().toPlainString()),
                    Map.of("clientOrderId", clientOrderId, "gridLevel", String.valueOf(intent.gridLevel())));

            return BotOrderView.of(entity);

        } catch (Exception e) {
            // Запись НАМЕРЕННО остаётся PENDING: мы не знаем, дошёл ли ордер до биржи.
            // Судьбу выяснит сверка — по clientOrderId, который уже сохранён.
            entity.setLastError(e.getMessage());
            orderRepo.save(entity);

            log.error("Постановка ордера не ответила, запись остаётся PENDING: bot={}, clientOrderId={}, err={}",
                    ctx.botId(), clientOrderId, e.getMessage(), e);
            throw e;
        }
    }

    // ==============================
    // CANCEL
    // ==============================

    @Override
    public void cancel(BotExecutionContext ctx, UUID botOrderId) {
        BotOrderEntity entity = orderRepo.findById(botOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + botOrderId));

        if (entity.getStatus().isTerminal()) {
            return;
        }
        if (entity.getExchangeOrderId() == null) {
            // Биржевого id нет — сначала выясним, существует ли ордер вообще.
            resolvePending(ctx, entity);
            if (entity.getExchangeOrderId() == null) {
                return;
            }
        }

        try {
            orders().cancel(ctx.accountId(), new OrderId(entity.getExchangeOrderId()));
            entity.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(entity);

            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_CANCELLED,
                    "Снят ордер " + entity.getClientOrderId(),
                    Map.of("clientOrderId", entity.getClientOrderId()));
        } catch (Exception e) {
            // Ордер мог исполниться прямо перед отменой — это не ошибка, это гонка.
            log.warn("Не удалось снять ордер {}: {}. Уточняю состояние.", entity.getClientOrderId(), e.getMessage());
            resolvePending(ctx, entity);
        }
    }

    @Override
    public int cancelAll(BotExecutionContext ctx) {
        List<BotOrderEntity> open = orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES);
        int cancelled = 0;
        for (BotOrderEntity o : open) {
            if (o.isDryRun()) {
                continue;
            }
            try {
                cancel(ctx, o.getId());
                cancelled++;
            } catch (Exception e) {
                log.warn("cancelAll: ордер {} снять не удалось: {}", o.getClientOrderId(), e.getMessage());
            }
        }
        return cancelled;
    }

    @Override
    public List<BotOrderView> openOrders(UUID botId) {
        return orderRepo.findAllByBotIdAndStatusIn(botId, OPEN_STATUSES).stream()
                .map(BotOrderView::of)
                .toList();
    }

    // ==============================
    // STREAM EVENT (fast path)
    // ==============================

    @Override
    public Optional<BotOrderView> applyOrderEvent(BotExecutionContext ctx, OrderState fromStream) {
        if (fromStream == null || fromStream.clientOrderId() == null) {
            return Optional.empty();
        }

        Optional<BotOrderEntity> found = orderRepo.findByClientOrderId(fromStream.clientOrderId().value());
        if (found.isEmpty()) {
            // Чужой ордер: ручная сделка в приложении брокера или другой бот.
            // Это штатная ситуация, а не ошибка.
            log.debug("Событие по неизвестному clientOrderId {} — не наше, пропускаю",
                    fromStream.clientOrderId().value());
            return Optional.empty();
        }

        BotOrderEntity entity = found.get();
        OrderStatus before = entity.getStatus();
        long executedBefore = entity.getExecutedLots();

        applyState(entity, fromStream);
        orderRepo.save(entity);

        emitFillEvents(ctx, entity, before, executedBefore);
        return Optional.of(BotOrderView.of(entity));
    }

    private void emitFillEvents(BotExecutionContext ctx, BotOrderEntity entity,
                                OrderStatus before, long executedBefore) {
        if (entity.getExecutedLots() > executedBefore) {
            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_FILLED,
                    "%s исполнено %d из %d по %s".formatted(
                            entity.getSide(), entity.getExecutedLots(), entity.getRequestedLots(),
                            entity.getAvgPrice() == null ? "?" : entity.getAvgPrice().toPlainString()),
                    Map.of("clientOrderId", entity.getClientOrderId(),
                            "gridLevel", String.valueOf(entity.getGridLevel())));
        }

        if (before != OrderStatus.REJECTED && entity.getStatus() == OrderStatus.REJECTED) {
            events.emit(ctx.botId(), BotEventLevel.WARN, BotEventType.ORDER_REJECTED,
                    "Биржа отклонила ордер " + entity.getClientOrderId(),
                    Map.of("clientOrderId", entity.getClientOrderId()));
        }
    }

    // ==============================
    // RECONCILE (slow path)
    // ==============================

    @Override
    public ReconcileResult reconcile(BotExecutionContext ctx) {
        List<BotOrderEntity> journalOpen = orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES);

        // Что биржа считает живым прямо сейчас.
        Map<String, OrderState> exchangeByClientId = new HashMap<>();
        for (OrderState s : orders().listOpen(ctx.accountId(), ctx.instrumentId())) {
            if (s.clientOrderId() != null) {
                exchangeByClientId.put(s.clientOrderId().value(), s);
            }
        }

        int resolvedPending = 0;
        for (BotOrderEntity entity : journalOpen) {
            if (entity.isDryRun()) {
                continue;
            }

            OrderState onExchange = exchangeByClientId.remove(entity.getClientOrderId());
            if (onExchange != null) {
                applyState(entity, onExchange);
                orderRepo.save(entity);
                continue;
            }

            // На бирже среди живых его нет: либо исполнился, либо снят, либо
            // (для PENDING) вовсе не был принят. Выясняем точно.
            if (resolvePending(ctx, entity)) {
                resolvedPending++;
            }
        }

        // Ордера, которые биржа знает, а журнал — нет. Для нашего инструмента и счёта
        // это либо ручная сделка, либо след прошлой жизни бота.
        int adoptedOrphans = exchangeByClientId.size();
        if (adoptedOrphans > 0) {
            log.warn("Bot {}: на бирже {} ордер(ов), которых нет в журнале", ctx.botId(), adoptedOrphans);
        }

        BigDecimal journalPosition = BigDecimal.valueOf(orderRepo.sumPositionLots(ctx.botId(), ctx.dryRun()));
        BigDecimal exchangePosition = fetchExchangePosition(ctx);
        BigDecimal mismatch = exchangePosition == null
                ? BigDecimal.ZERO
                : exchangePosition.subtract(journalPosition);

        ReconcileResult result = new ReconcileResult(
                openOrders(ctx.botId()),
                exchangePosition == null ? journalPosition : exchangePosition,
                riskGuard.usedCapital(ctx),
                resolvedPending,
                adoptedOrphans,
                mismatch
        );

        if (result.hasFindings()) {
            events.emit(ctx.botId(), BotEventLevel.WARN, BotEventType.RECONCILED,
                    "Сверка: дорешено %d, чужих на бирже %d, расхождение позиции %s"
                            .formatted(resolvedPending, adoptedOrphans, mismatch.toPlainString()),
                    Map.of());
        }
        return result;
    }

    /**
     * Выясняет судьбу ордера по НАШЕМУ clientOrderId.
     * Это и есть механизм, закрывающий дыру идемпотентности: биржевой id может быть
     * неизвестен, а спросить всё равно можно.
     *
     * @return true, если состояние удалось уточнить
     */
    private boolean resolvePending(BotExecutionContext ctx, BotOrderEntity entity) {
        try {
            Optional<OrderState> state = orders()
                    .getByClientOrderId(ctx.accountId(), new ClientOrderId(entity.getClientOrderId()));

            if (state.isPresent()) {
                applyState(entity, state.get());
                orderRepo.save(entity);
                return true;
            }

            // Биржа такого ордера не знает — значит он не был принят.
            // Ставить его заново безопасно, дубля не будет.
            entity.setStatus(OrderStatus.REJECTED);
            entity.setLastError("Биржа не знает ордера с таким clientOrderId — не был принят");
            orderRepo.save(entity);
            return true;

        } catch (Exception e) {
            // Не смогли выяснить — оставляем как есть. Лучше повторить сверку позже,
            // чем принять решение по догадке.
            log.warn("Не удалось уточнить состояние ордера {}: {}", entity.getClientOrderId(), e.getMessage());
            return false;
        }
    }

    private BigDecimal fetchExchangePosition(BotExecutionContext ctx) {
        try {
            return clientSupplier.get().accounts()
                    .getPosition(ctx.accountId(), ctx.instrumentId())
                    .map(p -> p.quantity())
                    .orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("Не удалось получить позицию с биржи: {}", e.getMessage());
            return null;
        }
    }

    // ==============================
    // HELPERS
    // ==============================

    private OrdersApi orders() {
        return clientSupplier.get().orders();
    }

    /** Переносит состояние с биржи в запись журнала, не затирая уже известное. */
    private static void applyState(BotOrderEntity entity, OrderState state) {
        if (state == null) {
            return;
        }
        if (state.status() != null && state.status() != OrderStatus.UNKNOWN) {
            entity.setStatus(state.status());
        }
        if (state.executedQuantity() != null) {
            entity.setExecutedLots(state.executedQuantity().longValue());
        }
        if (state.averageExecutedPrice() != null) {
            entity.setAvgPrice(state.averageExecutedPrice());
        }
        if (state.orderId() != null && state.orderId().value() != null && !state.orderId().value().isBlank()) {
            entity.setExchangeOrderId(state.orderId().value());
        }
        if (state.fee() != null && state.fee().actual() != null) {
            entity.setFee(state.fee().actual());
        }
    }
}
