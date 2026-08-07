package ru.larionov.backend.execution;

import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Неизменяемый снимок ордера для стратегии и UI: наружу JPA-сущность не отдаём. */
public record BotOrderView(
        UUID id,
        String clientOrderId,
        String exchangeOrderId,
        OrderSide side,
        OrderStatus status,
        Integer gridLevel,
        BigDecimal requestedQuantity,
        BigDecimal executedQuantity,
        BigDecimal limitPrice,
        BigDecimal avgPrice,
        BigDecimal fee,
        boolean feeActual,
        BigDecimal feeRate,
        String feeSource,
        String feeCurrency,
        BigDecimal exchangeLotSize,
        boolean dryRun,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {

    public static BotOrderView of(BotOrderEntity e) {
        return new BotOrderView(
                e.getId(),
                e.getClientOrderId(),
                e.getExchangeOrderId(),
                e.getSide(),
                e.getStatus(),
                e.getGridLevel(),
                e.getRequestedQuantity(),
                e.getExecutedQuantity(),
                e.getLimitPrice(),
                e.getAvgPrice(),
                e.getFee(),
                e.isFeeActual(),
                e.getFeeRate(),
                e.getFeeSource(),
                e.getFeeCurrency(),
                e.getExchangeLotSize(),
                e.isDryRun(),
                e.getLastError(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    /** Сколько ещё не исполнено. Никогда не отрицательно. */
    public BigDecimal remainingQuantity() {
        BigDecimal remaining = nvl(requestedQuantity).subtract(nvl(executedQuantity));
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
