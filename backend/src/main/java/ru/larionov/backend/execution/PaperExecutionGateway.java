package ru.larionov.backend.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.BotEventService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Бумажный режим: ордера идут в тот же журнал, но не на биржу.
 *
 * Исполнение симулируется по реальному потоку цен: заявка на покупку считается
 * исполненной, когда цена опустилась до её лимита, на продажу — когда поднялась.
 *
 * Осознанное упрощение: симуляция оптимистична — она не моделирует очередь заявок,
 * частичное исполнение и проскальзывание. Поэтому бумажный прогон проверяет
 * ЛОГИКУ стратегии и целостность журнала, но не её доходность.
 */
@Slf4j
@RequiredArgsConstructor
public class PaperExecutionGateway implements ExecutionGateway {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN);

    private final BotOrderRepository orderRepo;
    private final RiskGuard riskGuard;
    private final BotEventService events;
    private final AccountingService accounting;

    @Override
    public boolean isDryRun() {
        return true;
    }

    @Override
    public BotOrderView placeLimit(BotExecutionContext ctx, PlaceIntent intent) {
        // Квантование к шагу биржи делаем и здесь: бумажный прогон обязан упираться
        // ровно в те же ограничения, иначе он перестаёт что-либо проверять.
        BigDecimal quantity = ctx.quantizeDown(intent.quantity());
        if (quantity.signum() <= 0) {
            throw new RiskRejectedException(
                    "[paper] Количество %s меньше шага биржи %s — заявка не имеет смысла."
                            .formatted(plain(intent.quantity()), plain(ctx.quantityStep())));
        }
        PlaceIntent tradable = new PlaceIntent(
                intent.side(), quantity, intent.limitPrice(), intent.gridLevel(), intent.purpose());
        riskGuard.check(ctx, tradable);

        BotOrderEntity entity = orderRepo.save(BotOrderEntity.builder()
                .botId(ctx.botId())
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(UUID.randomUUID().toString())
                .side(tradable.side())
                .status(OrderStatus.NEW)
                .gridLevel(tradable.gridLevel())
                .purpose(tradable.purpose())
                .requestedQuantity(tradable.quantity())
                .executedQuantity(BigDecimal.ZERO)
                .limitPrice(tradable.limitPrice())
                .exchangeLotSize(ctx.exchangeLotSize())
                .dryRun(true)
                .build());

        riskGuard.recordPlacement(ctx.botId());
        events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_PLACED,
                "[paper] %s %s по %s".formatted(
                        tradable.side(), plain(tradable.quantity()), tradable.limitPrice().toPlainString()),
                Map.of("clientOrderId", entity.getClientOrderId()));

        return BotOrderView.of(entity);
    }

    @Override
    public void cancel(BotExecutionContext ctx, UUID botOrderId) {
        orderRepo.findById(botOrderId).ifPresent(entity -> {
            if (entity.getStatus().isTerminal()) {
                return;
            }
            entity.setStatus(OrderStatus.CANCELLED);
            orderRepo.save(entity);
            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_CANCELLED,
                    "[paper] Снят ордер " + entity.getClientOrderId(),
                    Map.of("clientOrderId", entity.getClientOrderId()));
        });
    }

    @Override
    public int cancelAll(BotExecutionContext ctx) {
        List<BotOrderEntity> open = orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES);
        int count = 0;
        for (BotOrderEntity o : open) {
            if (!o.isDryRun()) {
                continue;
            }
            cancel(ctx, o.getId());
            count++;
        }
        return count;
    }

    @Override
    public List<BotOrderView> openOrders(UUID botId) {
        return orderRepo.findAllByBotIdAndStatusIn(botId, OPEN_STATUSES).stream()
                .filter(BotOrderEntity::isDryRun)
                .map(BotOrderView::of)
                .toList();
    }

    @Override
    public List<BotOrderView> levelOrders(UUID botId, Instant since) {
        return orderRepo.findLevelOrders(botId, true, since == null ? Instant.EPOCH : since).stream()
                .map(BotOrderView::of)
                .toList();
    }

    /** В бумажном режиме событий от биржи нет — исполнение рождается из цен. */
    @Override
    public Optional<BotOrderView> applyOrderEvent(BotExecutionContext ctx, OrderState fromStream) {
        return Optional.empty();
    }

    @Override
    public List<BotOrderView> onPrice(BotExecutionContext ctx, LastPrice price) {
        if (price == null || price.price() == null || price.price().value() == null) {
            return List.of();
        }
        BigDecimal current = price.price().value();
        List<BotOrderView> filled = new ArrayList<>();

        for (BotOrderEntity o : orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES)) {
            if (!o.isDryRun() || o.getLimitPrice() == null || !o.getStatus().isActive()) {
                continue;
            }

            boolean crossed = o.getSide() == OrderSide.BUY
                    ? current.compareTo(o.getLimitPrice()) <= 0
                    : current.compareTo(o.getLimitPrice()) >= 0;

            if (!crossed) {
                continue;
            }

            o.setExecutedQuantity(o.getRequestedQuantity());
            o.setAvgPrice(o.getLimitPrice());
            o.setStatus(OrderStatus.FILLED);
            BotOrderEntity saved = orderRepo.save(o);
            accounting.recordOrderState(ctx, saved);

            events.emit(ctx.botId(), BotEventLevel.INFO, BotEventType.ORDER_FILLED,
                    "[paper] %s исполнено %s по %s".formatted(
                            o.getSide(), plain(o.getRequestedQuantity()), o.getLimitPrice().toPlainString()),
                    Map.of("clientOrderId", o.getClientOrderId(),
                            "gridLevel", String.valueOf(o.getGridLevel())));

            filled.add(BotOrderView.of(o));
        }
        return filled;
    }

    /**
     * Сверять не с чем — биржа о бумажных ордерах не знает. Возвращаем состояние журнала,
     * чтобы стратегия работала одинаково в обоих режимах.
     */
    @Override
    public ReconcileResult reconcile(BotExecutionContext ctx) {
        BigDecimal position = orderRepo.sumPositionQuantity(ctx.botId(), true);
        BigDecimal own = position == null ? BigDecimal.ZERO : position;
        // На бумаге счёта нет: остаток по определению равен журналу, расхождению неоткуда взяться.
        return new ReconcileResult(
                openOrders(ctx.botId()),
                own,
                own,
                riskGuard.usedCapital(ctx),
                0,
                0,
                BigDecimal.ZERO
        );
    }

    /** Количество для человека: 0.000001 не должно превратиться в 1E-6. */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
