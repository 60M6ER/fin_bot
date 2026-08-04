package ru.larionov.backend.dto;

import java.math.BigDecimal;

public record BotAccountingDto(
        boolean dryRun,
        BigDecimal cashFlow,
        BigDecimal costBasisOpen,
        BigDecimal realizedPnl,
        BigDecimal paidCommission,
        long openLots,
        BigDecimal averageEntryPrice,
        String currency
) {
}
