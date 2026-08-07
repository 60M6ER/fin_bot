package ru.larionov.backend.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.enums.TimeInForce;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.order.CommissionSource;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.BotEventService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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

    /**
     * Сколько ждём, прежде чем признать неподтверждённую запись непринятой.
     *
     * PENDING означает «мы не знаем», и это честно ровно до тех пор, пока у биржи есть
     * шанс ответить. Дальше неопределённость перестаёт быть осторожностью и становится
     * ловушкой: такая запись держит капитал в лимитах, считается открытой заявкой и
     * блокирует ликвидацию — навсегда, потому что сама она не разрешится никогда.
     */
    private static final Duration STALE_PENDING_AFTER = Duration.ofMinutes(2);

    private final BotOrderRepository orderRepo;
    private final RiskGuard riskGuard;
    private final BotEventService events;
    private final AccountingService accounting;
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
        PlaceIntent tradable = toTradable(ctx, intent);
        riskGuard.check(ctx, tradable);

        // Шаг 1: фиксируем намерение в журнале ДО обращения к бирже.
        // UUID даёт ровно 36 символов — предел поля orderId у T-Invest.
        String clientOrderId = UUID.randomUUID().toString();
        BotOrderEntity entity = orderRepo.save(BotOrderEntity.builder()
                .botId(ctx.botId())
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(clientOrderId)
                .side(tradable.side())
                .status(OrderStatus.PENDING)
                .gridLevel(tradable.gridLevel())
                .requestedQuantity(tradable.quantity())
                .executedQuantity(BigDecimal.ZERO)
                .limitPrice(tradable.limitPrice())
                .exchangeLotSize(ctx.exchangeLotSize())
                .dryRun(false)
                .build());

        // Шаг 2: сетевой вызов. В заявочные единицы биржи переводим здесь и только здесь.
        try {
            OrderResponse response = orders().placeLimit(new OrderRequest(
                    ctx.accountId(),
                    ctx.instrumentId(),
                    new ClientOrderId(clientOrderId),
                    tradable.side(),
                    ctx.toExchangeUnits(tradable.quantity()),
                    tradable.limitPrice(),
                    TimeInForce.GTC // у T-Invest всё равно DAY: GTC в протоколе нет
            ));

            persist(ctx, entity, response.state());
            entity.setExchangeOrderId(response.orderId() == null ? null : response.orderId().value());
            if (entity.getStatus() == OrderStatus.PENDING) {
                entity.setStatus(OrderStatus.NEW);
            }
            entity.setLastError(null);
            orderRepo.save(entity);

            riskGuard.recordPlacement(ctx.botId());
            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_PLACED,
                    "%s %s по %s".formatted(tradable.side(), plain(tradable.quantity()),
                            tradable.limitPrice().toPlainString()),
                    Map.of("clientOrderId", clientOrderId, "gridLevel", String.valueOf(tradable.gridLevel())));

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

    /**
     * Приводит намерение стратегии к тому, что биржа действительно примет.
     *
     * Живёт в гейтвее, а не в стратегии, по той же причине, что и риск-контроль:
     * стратегия не обязана знать про шаг количества конкретной площадки, а нарушить
     * его не должна даже по ошибке. Округляем ВНИЗ — заявка чуть меньше задуманной
     * безопасна, чуть больше выходит за обеспеченный бюджет.
     *
     * Отказ здесь честнее молчаливой отправки: биржа всё равно отвергнет заявку,
     * но уже без внятного объяснения, а запись останется висеть в PENDING.
     */
    private PlaceIntent toTradable(BotExecutionContext ctx, PlaceIntent intent) {
        BigDecimal quantity = ctx.quantizeDown(intent.quantity());
        if (quantity.signum() <= 0) {
            throw new RiskRejectedException(
                    ("Количество %s меньше шага биржи %s — заявка не имеет смысла. "
                            + "Увеличьте бюджет или уменьшите число уровней.")
                            .formatted(plain(intent.quantity()), plain(ctx.quantityStep())));
        }

        PlaceIntent tradable = quantity.compareTo(intent.quantity()) == 0
                ? intent
                : new PlaceIntent(intent.side(), quantity, intent.limitPrice(), intent.gridLevel());

        BigDecimal minNotional = ctx.minNotional();
        if (minNotional != null && tradable.notional().compareTo(minNotional) < 0) {
            throw new RiskRejectedException(
                    ("Сумма заявки %s меньше минимума биржи %s. "
                            + "Увеличьте бюджет или уменьшите число уровней сетки.")
                            .formatted(tradable.notional().stripTrailingZeros().toPlainString(),
                                    minNotional.stripTrailingZeros().toPlainString()));
        }
        return tradable;
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
            if (entity.getStatus().isTerminal()) {
                return;
            }
            if (entity.getExchangeOrderId() == null) {
                // Выяснить не удалось. Молча выйти нельзя: запись останется «открытой»
                // и заблокирует того, кто снимает заявки перед ликвидацией, — он ждёт
                // пустого списка, которого не дождётся. Но и списать её можно только
                // убедившись, что среди живых заявок биржи её действительно нет.
                if (isAbsentFromExchange(ctx, entity)) {
                    abandonIfStale(ctx, entity);
                }
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

    @Override
    public List<BotOrderView> recentOrders(UUID botId) {
        return orderRepo.findTop200ByBotIdOrderByCreatedAtDesc(botId).stream()
                .filter(o -> !o.isDryRun())
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
        if (!belongsToContext(entity, ctx)) {
            log.debug("Событие clientOrderId={} принадлежит боту {}, текущий бот {} — пропускаю",
                    entity.getClientOrderId(), entity.getBotId(), ctx.botId());
            return Optional.empty();
        }
        OrderStatus before = entity.getStatus();
        BigDecimal executedBefore = entity.getExecutedQuantity();

        persist(ctx, entity, fromStream);

        emitFillEvents(ctx, entity, before, executedBefore);
        return Optional.of(BotOrderView.of(entity));
    }

    private boolean belongsToContext(BotOrderEntity entity, BotExecutionContext ctx) {
        return entity.getBotId().equals(ctx.botId())
                && entity.getConnectionId().equals(ctx.connectionId())
                && entity.getAccountId().equals(ctx.accountId().value())
                && entity.getInstrumentUid().equals(ctx.instrumentId().primary());
    }

    private void emitFillEvents(BotExecutionContext ctx, BotOrderEntity entity,
                                OrderStatus before, BigDecimal executedBefore) {
        if (entity.getExecutedQuantity().compareTo(nvl(executedBefore)) > 0) {
            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_FILLED,
                    "%s исполнено %s из %s по %s".formatted(
                            entity.getSide(), plain(entity.getExecutedQuantity()),
                            plain(entity.getRequestedQuantity()),
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
        Map<UUID, BotOrderEntity> toReconcile = new HashMap<>();
        for (BotOrderEntity o : orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES)) {
            toReconcile.put(o.getId(), o);
        }
        for (BotOrderEntity o : orderRepo.findAllByBotIdAndDryRunAndFeeActualFalseAndExecutedQuantityGreaterThan(
                ctx.botId(), false, BigDecimal.ZERO)) {
            toReconcile.put(o.getId(), o);
        }

        // Что биржа считает живым прямо сейчас.
        Map<String, OrderState> exchangeByClientId = new HashMap<>();
        for (OrderState s : orders().listOpen(ctx.accountId(), ctx.instrumentId())) {
            if (s.clientOrderId() != null) {
                exchangeByClientId.put(s.clientOrderId().value(), s);
            }
        }

        int resolvedPending = 0;
        for (BotOrderEntity entity : toReconcile.values()) {
            if (entity.isDryRun()) {
                continue;
            }

            OrderState onExchange = exchangeByClientId.remove(entity.getClientOrderId());
            if (onExchange != null) {
                persist(ctx, entity, onExchange);
                continue;
            }

            // На бирже среди живых его нет: либо исполнился, либо снят, либо
            // (для PENDING) вовсе не был принят. Выясняем точно.
            if (resolvePending(ctx, entity)) {
                resolvedPending++;
                continue;
            }

            // Состояние выяснить не удалось. Но listOpen выше отработал, то есть связь
            // с биржей есть и полный список её живых заявок по этому счёту и инструменту
            // мы уже видели — нашей записи в нём не было. Для давно висящей записи без
            // биржевого id это достаточное основание считать её непринятой.
            if (abandonIfStale(ctx, entity)) {
                resolvedPending++;
            }
        }

        // Ордера, которые биржа знает, а журнал — нет. Для нашего инструмента и счёта
        // это либо ручная сделка, либо след прошлой жизни бота.
        int adoptedOrphans = exchangeByClientId.size();
        if (adoptedOrphans > 0) {
            log.warn("Bot {}: на бирже {} ордер(ов), которых нет в журнале", ctx.botId(), adoptedOrphans);
        }

        BigDecimal journalPosition = nvl(orderRepo.sumPositionQuantity(ctx.botId(), ctx.dryRun()));
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
                persist(ctx, entity, state.get());
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

    /**
     * Есть ли запись среди живых заявок биржи.
     *
     * Вызывается там, где полного списка ещё не запрашивали. Если спросить не удалось —
     * отвечаем «не знаем» в безопасную сторону: молчание биржи не доказательство
     * отсутствия ордера.
     */
    private boolean isAbsentFromExchange(BotExecutionContext ctx, BotOrderEntity entity) {
        try {
            return orders().listOpen(ctx.accountId(), ctx.instrumentId()).stream()
                    .noneMatch(s -> s.clientOrderId() != null
                            && entity.getClientOrderId().equals(s.clientOrderId().value()));
        } catch (Exception e) {
            log.warn("Не удалось получить список живых заявок для {}: {}",
                    entity.getClientOrderId(), e.getMessage());
            return false;
        }
    }

    /**
     * Признаёт непринятой запись, которая давно висит в PENDING и не подтверждена биржей.
     *
     * Вызывать только убедившись, что среди живых заявок биржи её нет: списать ордер,
     * который на самом деле стоит в стакане, — это ровно тот дубль, ради недопущения
     * которого построена вся идемпотентность постановки.
     *
     * Остальные условия намеренно узкие. Биржевого id нет — значит подтверждения
     * постановки мы не получали ни разу. Исполненных лотов нет — значит по ней не
     * приходило ни одного события, а стрим адресует события по нашему же clientOrderId.
     * Прошло больше {@link #STALE_PENDING_AFTER} — значит это не гонка с только что
     * отправленным запросом.
     *
     * Ошибиться в другую сторону тоже дорого: не разрешив такую запись, мы навсегда
     * оставляем боту фантомную заявку, которая занимает капитал и делает сетку
     * неспособной ни торговать, ни закрыться.
     *
     * @return true, если запись переведена в терминальный статус
     */
    private boolean abandonIfStale(BotExecutionContext ctx, BotOrderEntity entity) {
        if (entity.getStatus() != OrderStatus.PENDING
                || entity.getExchangeOrderId() != null
                || nvl(entity.getExecutedQuantity()).signum() > 0) {
            return false;
        }
        Instant createdAt = entity.getCreatedAt();
        if (createdAt == null || createdAt.isAfter(Instant.now().minus(STALE_PENDING_AFTER))) {
            return false;
        }

        entity.setStatus(OrderStatus.REJECTED);
        entity.setLastError("Постановка не подтверждена биржей за " + STALE_PENDING_AFTER.toMinutes()
                + " мин, среди живых заявок её нет — считаю непринятой");
        orderRepo.save(entity);

        log.warn("Bot {}: запись {} висела в PENDING без подтверждения биржей, помечена непринятой",
                ctx.botId(), entity.getClientOrderId());
        events.emit(ctx.botId(), BotEventLevel.WARN, BotEventType.ORDER_REJECTED,
                "Заявка %s так и не подтверждена биржей — считаю непринятой".formatted(entity.getClientOrderId()),
                Map.of("clientOrderId", entity.getClientOrderId(),
                        "gridLevel", String.valueOf(entity.getGridLevel())));
        return true;
    }

    /**
     * Позиция с биржи в единицах базового актива.
     *
     * Приведения больше нет и не нужно: брокер отдаёт позицию в штуках, домен считает
     * в них же. Раньше здесь стояло деление на размер лота, и пропуск этого деления
     * однажды стоил реальных денег — при лотности 10 позиция в 1 лот выглядела как 10,
     * и стратегия выставляла продажи на весь мнимый объём. Теперь у ошибки нет места:
     * единица одна на всём пути.
     */
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

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Количество для человека: 0.000001 не должно превратиться в 1E-6. */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private void persist(BotExecutionContext ctx, BotOrderEntity entity, OrderState state) {
        applyState(ctx, entity, state);
        BotOrderEntity saved = orderRepo.save(entity);
        accounting.recordOrderState(ctx, saved);
    }

    /** Переносит состояние с биржи в запись журнала, не затирая уже известное. */
    private void applyState(BotExecutionContext ctx, BotOrderEntity entity, OrderState state) {
        if (state == null) {
            return;
        }
        if (entity.getExchangeLotSize() == null || entity.getExchangeLotSize().signum() <= 0) {
            entity.setExchangeLotSize(ctx.exchangeLotSize());
        }
        if (state.status() != null && state.status() != OrderStatus.UNKNOWN) {
            entity.setStatus(state.status());
        }
        if (state.executedQuantity() != null) {
            // Биржа отвечает СВОИМИ заявочными единицами — переводим в базовые здесь,
            // на той же границе, где переводили в обратную сторону при постановке.
            BigDecimal executed = ctx.fromExchangeUnits(state.executedQuantity());
            entity.setExecutedQuantity(nvl(entity.getExecutedQuantity()).max(executed));
        }
        if (state.averageExecutedPrice() != null) {
            entity.setAvgPrice(state.averageExecutedPrice());
        }
        if (state.orderId() != null && state.orderId().value() != null && !state.orderId().value().isBlank()) {
            entity.setExchangeOrderId(state.orderId().value());
        }
        applyFee(ctx, entity, state);
    }

    private void applyFee(BotExecutionContext ctx, BotOrderEntity entity, OrderState state) {
        OrderFee fee = state.fee();
        if ((fee == null || fee.amount() == null)
                && nvl(entity.getExecutedQuantity()).signum() > 0
                && !entity.isFeeActual()) {
            fee = estimateFee(ctx, entity);
        }
        if (fee == null || fee.amount() == null) {
            return;
        }

        // Факт всегда побеждает оценку, оценка факт не затирает.
        if (entity.isFeeActual() && !fee.actual()) {
            return;
        }
        entity.setFee(fee.amount());
        entity.setFeeActual(fee.actual());
        entity.setFeeRate(fee.rate());
        entity.setFeeSource(fee.source() == null ? null : fee.source().name());
        entity.setFeeCurrency(fee.currency());
    }

    private OrderFee estimateFee(BotExecutionContext ctx, BotOrderEntity entity) {
        try {
            FeeInfo fees = clientSupplier.get().fees().getFeeInfo(ctx.accountId(), ctx.instrumentId());
            if (fees == null) {
                return null;
            }
            BigDecimal rate = fees.makerRateFor(entity.getSide());
            BigDecimal price = entity.getAvgPrice() != null ? entity.getAvgPrice() : entity.getLimitPrice();
            if (rate == null || price == null) {
                return null;
            }
            BigDecimal amount = price.multiply(nvl(entity.getExecutedQuantity())).multiply(rate);
            return OrderFee.estimated(rate, amount, null, CommissionSource.BROKER_RATE_ESTIMATE);
        } catch (Exception e) {
            log.debug("Не удалось оценить комиссию ордера {}: {}", entity.getClientOrderId(), e.getMessage());
            return null;
        }
    }
}
