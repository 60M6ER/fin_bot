package ru.larionov.backend.accounting;

import ru.larionov.backend.enums.OrderPurpose;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Открытая и ещё не закрытая партия: сколько набрано и во сколько это обошлось.
 *
 * Раньше называлась OpenLot и хранила лоты вместе с лотностью. Слово «лот» убрано
 * намеренно: лотом теперь называется только заявочная единица биржи, а здесь речь
 * о единицах базового актива. {@code quantity} дробное — на криптобирже партия
 * вполне может быть 0.00042 монеты.
 *
 * <h3>Знак</h3>
 * Оба числа ЗНАКОВЫЕ, и знак у них общий:
 * <ul>
 *   <li>лонг: количество положительно, себестоимость положительна — это потраченные
 *       деньги вместе с комиссией;</li>
 *   <li>шорт: количество отрицательно, себестоимость отрицательна — это полученные
 *       деньги за вычетом комиссии, взятые с минусом.</li>
 * </ul>
 *
 * Правило одно на оба случая: <b>себестоимость есть МИНУС денежный эффект открывающей
 * сделки</b>. Из него же следует, что тождество {@code realizedPnl = cashFlow +
 * costBasisOpen} остаётся верным для обеих сторон: себестоимость по определению гасит
 * денежный эффект ещё не закрытых сделок.
 *
 * Себестоимость шорта — не «во сколько обошлось», а обязательство: столько придётся
 * отдать, чтобы вернуть занятое.
 */
record OpenParcel(
        Integer gridLevel,
        BigDecimal quantity,
        BigDecimal costBasis,
        /**
         * Чья это партия: сетки или восстановительного плеча.
         *
         * Без этого признака при одновременной работе плеча и сетки закрытие плеча
         * съело бы партии сетки по порядку поступления — у него нет уровня, а
         * «без уровня» в отборе означает «любая партия». Результатом был бы цикл
         * сетки, закрытый чужой себестоимостью, и оба итога неверны молча.
         */
        OrderPurpose purpose
) {

    /** Партия сетки: назначение по умолчанию и единственное до появления плеча. */
    OpenParcel(Integer gridLevel, BigDecimal quantity, BigDecimal costBasis) {
        this(gridLevel, quantity, costBasis, OrderPurpose.GRID);
    }

    /** Партии плеча живут отдельно от сеточных и не смешиваются с ними. */
    boolean isHedge() {
        return purpose == OrderPurpose.HEDGE || purpose == OrderPurpose.RECOVERY;
    }

    /** Размер партии без знака: им меряется, сколько ещё можно закрыть. */
    BigDecimal magnitude() {
        return quantity.abs();
    }

    /** Лонговая партия или шортовая. */
    boolean isLong() {
        return quantity.signum() >= 0;
    }

    /**
     * Часть партии размером {@code taken} (по модулю): себестоимость делится
     * пропорционально и сохраняет знак партии.
     */
    OpenParcel take(BigDecimal taken) {
        BigDecimal size = magnitude();
        if (taken.compareTo(size) >= 0) {
            return this;
        }
        BigDecimal part = costBasis.multiply(taken)
                .divide(size, 18, RoundingMode.HALF_UP);
        return new OpenParcel(gridLevel, signed(taken), part, purpose);
    }

    /** Остаток партии после изъятия {@code taken}, либо null, если партия израсходована. */
    OpenParcel remainingAfter(BigDecimal taken) {
        BigDecimal size = magnitude();
        if (taken.compareTo(size) >= 0) {
            return null;
        }
        OpenParcel takenPart = take(taken);
        return new OpenParcel(gridLevel,
                signed(size.subtract(taken)),
                costBasis.subtract(takenPart.costBasis()),
                purpose);
    }

    /** Придаёт величине знак этой партии. */
    private BigDecimal signed(BigDecimal magnitude) {
        return isLong() ? magnitude : magnitude.negate();
    }
}
