package ru.larionov.backend.accounting;

import java.math.BigDecimal;

/**
 * Неизменяемый снимок открытой позиции, восстановленный из денежной книги.
 *
 * Внимание на единицы: {@code openLots} — в ЛОТАХ, {@code averageEntryPrice} и
 * {@code openShares} — в ШТУКАХ. {@code openShares} выставлен наружу именно для
 * рыночной оценки: {@code marketValue = openShares × цена} не требует знать лотность,
 * которая доступна только пока бот запущен.
 */
public record Inventory(
        long openLots,
        BigDecimal costBasisOpen,
        BigDecimal averageEntryPrice,
        long openShares
) {

    public static Inventory empty() {
        return new Inventory(0, BigDecimal.ZERO, null, 0);
    }
}
