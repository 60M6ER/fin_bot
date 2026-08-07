package ru.larionov.backend.accounting;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Купленная и ещё не проданная партия: сколько куплено и во сколько обошлось.
 *
 * Раньше называлась OpenLot и хранила лоты вместе с лотностью. Слово «лот» убрано
 * намеренно: лотом теперь называется только заявочная единица биржи, а здесь речь
 * о единицах базового актива. {@code quantity} дробное — на криптобирже партия
 * вполне может быть 0.00042 монеты.
 */
record OpenParcel(
        Integer gridLevel,
        BigDecimal quantity,
        BigDecimal costBasis
) {

    /** Часть партии размером {@code taken}: себестоимость делится пропорционально. */
    OpenParcel take(BigDecimal taken) {
        if (taken.compareTo(quantity) >= 0) {
            return this;
        }
        BigDecimal part = costBasis.multiply(taken)
                .divide(quantity, 18, RoundingMode.HALF_UP);
        return new OpenParcel(gridLevel, taken, part);
    }

    /** Остаток партии после изъятия {@code taken}, либо null, если партия израсходована. */
    OpenParcel remainingAfter(BigDecimal taken) {
        if (taken.compareTo(quantity) >= 0) {
            return null;
        }
        OpenParcel takenPart = take(taken);
        return new OpenParcel(gridLevel, quantity.subtract(taken),
                costBasis.subtract(takenPart.costBasis()));
    }
}
