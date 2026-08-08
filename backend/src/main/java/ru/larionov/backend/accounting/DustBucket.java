package ru.larionov.backend.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Накопленная пыль: непродаваемые хвосты закрытых циклов и их общая себестоимость.
 *
 * Хвост остаётся после каждого цикла там, где комиссия удерживается монетой:
 * зачисляется 0.09101534, а продать при шаге 0.000001 можно 0.091015. По отдельности
 * такой остаток биржа не примет ни при каких условиях. Вместе — примет, и тогда
 * пыль уходит одной заявкой по средней себестоимости плюс минимальная наценка.
 *
 * Себестоимость здесь общая, а не средняя по замыслу: у каждого хвоста своя цена,
 * и {@link #averagePrice()} — это цена продажи всей корзины разом, а не утверждение
 * о том, что все хвосты стоили одинаково.
 */
public record DustBucket(BigDecimal quantity, BigDecimal costBasis) {

    public static DustBucket empty() {
        return new DustBucket(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public DustBucket {
        quantity = quantity == null ? BigDecimal.ZERO : quantity;
        costBasis = costBasis == null ? BigDecimal.ZERO : costBasis;
    }

    public boolean isEmpty() {
        return quantity.signum() <= 0;
    }

    /** Во сколько обошлась единица накопленного. null — копить ещё нечего. */
    public BigDecimal averagePrice() {
        return isEmpty() ? null : costBasis.divide(quantity, 9, RoundingMode.HALF_UP);
    }
}
