package ru.larionov.backend.dto;

import java.math.BigDecimal;

/**
 * Итоги денежной книги бота. Только то, что выводится из журнала: рыночную оценку
 * и бюджет добавляет поверх {@code BotValuationService}.
 *
 * Внимание на единицы: {@code openLots} в ЛОТАХ, {@code averageEntryPrice} и
 * {@code openShares} — в ШТУКАХ.
 */
public record BotAccountingDto(
        boolean dryRun,
        BigDecimal cashFlow,
        BigDecimal costBasisOpen,
        BigDecimal realizedPnl,
        BigDecimal paidCommission,
        long openLots,
        BigDecimal averageEntryPrice,
        String currency,
        long openShares
) {
}
