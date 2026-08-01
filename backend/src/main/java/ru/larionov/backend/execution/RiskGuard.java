package ru.larionov.backend.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.TradingSwitch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Проверка лимитов перед постановкой ордера.
 *
 * Живёт внутри гейтвея, а не в стратегии: стратегия не должна иметь возможности
 * обойти лимиты — ни по невнимательности, ни из-за бага в своей логике.
 *
 * Считает по журналу, а не по памяти, поэтому лимиты переживают рестарт: бот,
 * перезапущенный после того, как выбрал дневной лимит ордеров, не начнёт с нуля.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskGuard {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN);

    private final BotOrderRepository orderRepo;
    private final TradingSwitch tradingSwitch;

    /**
     * Отметки времени последних постановок для минутного лимита.
     * Именно этот лимит — новый по сравнению с поллинговой схемой: раньше частоту
     * действий естественно ограничивал период опроса, на стриме такого ограничителя нет,
     * и баг в реакции на события способен высадить сотни ордеров за минуту.
     */
    private final Map<UUID, ConcurrentLinkedDeque<Instant>> recentPlacements = new ConcurrentHashMap<>();

    public void check(BotExecutionContext ctx, PlaceIntent intent) {
        // Глобальный стоп-кран проверяем первым: он должен действовать немедленно,
        // не дожидаясь остановки ботов.
        if (!ctx.dryRun() && !tradingSwitch.isEnabled()) {
            throw new RiskRejectedException(
                    "Торговля остановлена глобальным стоп-краном (Настройки → Торговля).");
        }

        checkOrdersPerMinute(ctx);
        checkOrdersPerDay(ctx);
        checkPosition(ctx, intent);
        checkCapital(ctx, intent);
    }

    /** Вызывается ПОСЛЕ успешной постановки: считаем только состоявшиеся заявки. */
    public void recordPlacement(UUID botId) {
        recentPlacements
                .computeIfAbsent(botId, __ -> new ConcurrentLinkedDeque<>())
                .addLast(Instant.now());
    }

    public void forget(UUID botId) {
        recentPlacements.remove(botId);
    }

    private void checkOrdersPerMinute(BotExecutionContext ctx) {
        Integer limit = ctx.maxOrdersPerMinute();
        if (limit == null || limit <= 0) {
            return;
        }

        ConcurrentLinkedDeque<Instant> marks = recentPlacements.computeIfAbsent(
                ctx.botId(), __ -> new ConcurrentLinkedDeque<>());

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        while (!marks.isEmpty() && marks.peekFirst().isBefore(cutoff)) {
            marks.pollFirst();
        }

        if (marks.size() >= limit) {
            throw new RiskRejectedException(
                    "Превышен лимит ордеров в минуту (" + limit + "). Похоже на разгон циклом.");
        }
    }

    private void checkOrdersPerDay(BotExecutionContext ctx) {
        Integer limit = ctx.maxOrdersPerDay();
        if (limit == null || limit <= 0) {
            return;
        }
        long today = orderRepo.countByBotIdAndCreatedAtAfter(ctx.botId(), Instant.now().minus(Duration.ofDays(1)));
        if (today >= limit) {
            throw new RiskRejectedException(
                    "Превышен суточный лимит ордеров (" + limit + "), выставлено " + today + ".");
        }
    }

    private void checkPosition(BotExecutionContext ctx, PlaceIntent intent) {
        Long limit = ctx.maxPositionLots();
        if (limit == null || limit <= 0) {
            return;
        }

        long current = orderRepo.sumPositionLots(ctx.botId(), ctx.dryRun());
        // Проверяем худший случай: как если бы заявка исполнилась целиком.
        long projected = intent.side() == OrderSide.BUY
                ? current + intent.lots()
                : current - intent.lots();

        if (Math.abs(projected) > limit) {
            throw new RiskRejectedException(
                    "Позиция вышла бы за лимит: " + projected + " лот(ов) при потолке " + limit + ".");
        }
    }

    private void checkCapital(BotExecutionContext ctx, PlaceIntent intent) {
        BigDecimal limit = ctx.maxCapital();
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        // Продажа капитал не занимает — она его высвобождает.
        if (intent.side() != OrderSide.BUY) {
            return;
        }

        BigDecimal used = usedCapital(ctx);
        BigDecimal projected = used.add(intent.notional());

        if (projected.compareTo(limit) > 0) {
            throw new RiskRejectedException(
                    "Задействованный капитал вышел бы за лимит: " + projected.toPlainString()
                            + " при потолке " + limit.toPlainString() + ".");
        }
    }

    /** Деньги, занятые открытыми заявками на покупку и уже набранной позицией. */
    public BigDecimal usedCapital(BotExecutionContext ctx) {
        List<BotOrderEntity> open = orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES);

        BigDecimal reserved = BigDecimal.ZERO;
        for (BotOrderEntity o : open) {
            if (o.isDryRun() != ctx.dryRun() || o.getSide() != OrderSide.BUY || o.getLimitPrice() == null) {
                continue;
            }
            reserved = reserved.add(o.getLimitPrice().multiply(BigDecimal.valueOf(o.remainingLots())));
        }

        long positionLots = orderRepo.sumPositionLots(ctx.botId(), ctx.dryRun());
        if (positionLots > 0) {
            BigDecimal avg = averageEntryPrice(ctx);
            if (avg != null) {
                reserved = reserved.add(avg.multiply(BigDecimal.valueOf(positionLots)));
            }
        }
        return reserved;
    }

    /** Средняя цена входа по журналу — грубая, но достаточная для оценки занятого капитала. */
    private BigDecimal averageEntryPrice(BotExecutionContext ctx) {
        List<BotOrderEntity> all = orderRepo.findTop200ByBotIdOrderByCreatedAtDesc(ctx.botId());

        BigDecimal sum = BigDecimal.ZERO;
        long lots = 0;
        for (BotOrderEntity o : all) {
            if (o.isDryRun() != ctx.dryRun() || o.getSide() != OrderSide.BUY || o.getExecutedLots() <= 0) {
                continue;
            }
            BigDecimal price = o.getAvgPrice() != null ? o.getAvgPrice() : o.getLimitPrice();
            if (price == null) {
                continue;
            }
            sum = sum.add(price.multiply(BigDecimal.valueOf(o.getExecutedLots())));
            lots += o.getExecutedLots();
        }
        return lots == 0 ? null : sum.divide(BigDecimal.valueOf(lots), 9, java.math.RoundingMode.HALF_UP);
    }
}
