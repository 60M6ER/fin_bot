package ru.larionov.backend.dto;

import java.math.BigDecimal;

/**
 * Итоги денежной книги бота. Только то, что выводится из журнала: рыночную оценку
 * и бюджет добавляет поверх {@code BotValuationService}.
 *
 * Единица одна — ЕДИНИЦЫ БАЗОВОГО АКТИВА: {@code openQuantity} в них,
 * {@code averageEntryPrice} — за одну такую единицу.
 */
public record BotAccountingDto(
        boolean dryRun,
        BigDecimal cashFlow,
        BigDecimal costBasisOpen,
        BigDecimal realizedPnl,
        BigDecimal paidCommission,
        BigDecimal openQuantity,
        BigDecimal averageEntryPrice,
        String currency
) {
}
