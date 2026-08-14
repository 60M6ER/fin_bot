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
        String currency,
        /**
         * Короткая часть позиции ПО МОДУЛЮ.
         *
         * Отдельно от openQuantity, потому что то — нетто: у бота с длинной и короткой
         * ногами оно может быть нулём при двух живых позициях. Обеспечение же занимает
         * именно короткая часть, и считать его от нетто значило бы обнулить его ровно
         * там, где занято больше всего.
         */
        BigDecimal shortQuantity
) {

    /** Итоги без разбивки по сторонам: книга без единой короткой позиции. */
    public BotAccountingDto(boolean dryRun, BigDecimal cashFlow, BigDecimal costBasisOpen,
                            BigDecimal realizedPnl, BigDecimal paidCommission,
                            BigDecimal openQuantity, BigDecimal averageEntryPrice,
                            String currency) {
        this(dryRun, cashFlow, costBasisOpen, realizedPnl, paidCommission,
                openQuantity, averageEntryPrice, currency, BigDecimal.ZERO);
    }
}
