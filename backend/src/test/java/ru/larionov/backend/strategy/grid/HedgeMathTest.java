package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Арифметика восстановительного плеча.
 *
 * Проверяется не «функция что-то вернула», а то, что по её цене эпизод действительно
 * выходит в ноль: каждый расчёт пересчитывается обратно, по деньгам. Ошибка здесь не
 * падает и не видна в логах — она превращается в убыток, который выглядит как рынок.
 */
class HedgeMathTest {

    private static final BigDecimal NO_FEE = BigDecimal.ZERO;
    private static final BigDecimal NO_CARRY = BigDecimal.ZERO;

    /**
     * Опорный пример без издержек, который можно пересчитать в уме.
     *
     * Куплено 10 по 110 (себестоимость 1100), цена упала до 100. Продаём 40:
     * десять закрывают лонг с убытком 100, тридцать открывают шорт. Чтобы отбить
     * сотню на тридцати штуках, цене надо упасть ещё на 3.33 — то есть втрое меньше,
     * чем она уже упала. В этом и весь смысл плеча.
     */
    @Test
    @DisplayName("плечо ×4 требует вчетверо меньшего движения, чем уже случилось")
    void leverageShortensTheDistanceToBreakEven() {
        HedgeMath.Plan plan = HedgeMath.plan(
                GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), NO_CARRY, 0);

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.totalQuantity()).isEqualByComparingTo("40");
        assertThat(plan.hedgeQuantity()).isEqualByComparingTo("30");
        assertThat(plan.realizedOnClose()).isEqualByComparingTo("-100");
        assertThat(plan.targetPrice()).isEqualByComparingTo("96.666666667");

        assertEpisodeClosesAtZero(plan, GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), NO_FEE, NO_CARRY, 0);
    }

    /** Комиссия входит дважды и сдвигает цель ниже, а не выше. */
    @Test
    @DisplayName("комиссия делает цель строже")
    void feeMakesTheTargetStricter() {
        HedgeMath.Plan free = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), NO_CARRY, 0);
        HedgeMath.Plan paid = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                new BigDecimal("0.003"), new BigDecimal("4"), NO_CARRY, 0);

        assertThat(paid.targetPrice()).isLessThan(free.targetPrice());
        assertEpisodeClosesAtZero(paid, GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("0.003"), NO_CARRY, 0);
    }

    /**
     * Перенос — та издержка, которую наивная формула не видит вовсе.
     * Чем дольше рассчитываем держать плечо, тем ниже обязана быть цель.
     */
    @Test
    @DisplayName("перенос сдвигает цель тем сильнее, чем дольше удержание")
    void carryPushesTheTargetFurther() {
        BigDecimal carry = new BigDecimal("0.001");
        HedgeMath.Plan oneDay = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), carry, 1);
        HedgeMath.Plan tenDays = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), carry, 10);

        assertThat(tenDays.targetPrice()).isLessThan(oneDay.targetPrice());
        assertThat(tenDays.carryCost()).isEqualByComparingTo(oneDay.carryCost().multiply(BigDecimal.TEN));

        assertEpisodeClosesAtZero(tenDays, GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), NO_FEE, carry, 10);
    }

    /** Зеркало: убыточный шорт переворачивается покупкой и ждёт РОСТА. */
    @Test
    @DisplayName("шорт переворачивается зеркально, цель выше входа")
    void shortFlipsMirrored() {
        // Продали 10 по 90 (получили 900), цена выросла до 100 — шорт в убытке.
        HedgeMath.Plan plan = HedgeMath.plan(
                GridDirection.SHORT,
                new BigDecimal("10"), new BigDecimal("-900"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), NO_CARRY, 0);

        assertThat(plan.accepted()).isTrue();
        assertThat(plan.realizedOnClose()).isEqualByComparingTo("-100");
        assertThat(plan.targetPrice())
                .as("перевернувшись в лонг, ждём роста — цель выше входа")
                .isGreaterThan(new BigDecimal("100"));

        assertEpisodeClosesAtZero(plan, GridDirection.SHORT,
                new BigDecimal("10"), new BigDecimal("-900"), NO_FEE, NO_CARRY, 0);
    }

    /** Больший множитель — ближе цель: движения нужно меньше. */
    @Test
    @DisplayName("чем больше множитель, тем ближе цель")
    void biggerMultiplierMeansCloserTarget() {
        BigDecimal two = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("2"), NO_CARRY, 0).targetPrice();
        BigDecimal eight = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("8"), NO_CARRY, 0).targetPrice();

        assertThat(eight).isGreaterThan(two);
    }

    // ==============================
    // ОТКАЗЫ
    // ==============================

    /**
     * Убыток настолько велик, что плечо его не отбивает даже при нулевой цене.
     * Такой план обязан быть отвергнут, а не выдан с отрицательной ценой.
     */
    @Test
    @DisplayName("неподъёмный убыток отвергается, а не даёт отрицательную цену")
    void hopelessLossIsRefused() {
        HedgeMath.Plan plan = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("5000"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("2"), NO_CARRY, 0);

        assertThat(plan.accepted()).isFalse();
        assertThat(plan.refusal()).contains("не способно отбить");
    }

    /** k=1 — это просто закрытие позиции, отбивать убыток нечем. */
    @Test
    @DisplayName("множитель без плеча отвергается")
    void multiplierOfOneIsRefused() {
        HedgeMath.Plan plan = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("1100"), new BigDecimal("100"),
                NO_FEE, BigDecimal.ONE, NO_CARRY, 0);

        assertThat(plan.accepted()).isFalse();
        assertThat(plan.refusal()).contains("больше единицы");
    }

    /**
     * Прибыльную позицию переворачивать незачем, и расчёт это замечает: цель
     * оказалась бы по ту же сторону от входа, что и он сам.
     */
    @Test
    @DisplayName("позиция в прибыли переворачивать себя не даёт")
    void profitablePositionIsRefused() {
        HedgeMath.Plan plan = HedgeMath.plan(GridDirection.LONG,
                new BigDecimal("10"), new BigDecimal("900"), new BigDecimal("100"),
                NO_FEE, new BigDecimal("4"), NO_CARRY, 0);

        assertThat(plan.accepted()).isFalse();
        assertThat(plan.refusal()).contains("по ту же сторону");
    }

    @Test
    @DisplayName("пустая позиция и отсутствие цены отвергаются")
    void emptyInputsAreRefused() {
        assertThat(HedgeMath.plan(GridDirection.LONG, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("100"), NO_FEE, new BigDecimal("4"), NO_CARRY, 0).accepted()).isFalse();
        assertThat(HedgeMath.plan(GridDirection.LONG, new BigDecimal("10"), new BigDecimal("1100"),
                null, NO_FEE, new BigDecimal("4"), NO_CARRY, 0).accepted()).isFalse();
    }

    // ==============================
    // ОБРАТНАЯ ПРОВЕРКА ПО ДЕНЬГАМ
    // ==============================

    /**
     * Пересчитывает эпизод по деньгам и требует нуля.
     *
     * Это и есть настоящая проверка: формулу можно вывести неверно и получить
     * правдоподобное число, но сойтись по деньгам оно тогда не сможет.
     */
    private void assertEpisodeClosesAtZero(HedgeMath.Plan plan, GridDirection direction,
                                           BigDecimal quantity, BigDecimal costBasis,
                                           BigDecimal fee, BigDecimal carry, int days) {
        BigDecimal entry = plan.entryPrice();
        BigDecimal target = plan.targetPrice();
        BigDecimal hedge = plan.hedgeQuantity();

        BigDecimal closeOldCash;
        BigDecimal openHedgeCash;
        BigDecimal closeHedgeCash;
        if (direction == GridDirection.LONG) {
            // Продаём всё: закрываем лонг и открываем шорт. Потом откупаем шорт.
            closeOldCash = quantity.multiply(entry).multiply(BigDecimal.ONE.subtract(fee));
            openHedgeCash = hedge.multiply(entry).multiply(BigDecimal.ONE.subtract(fee));
            closeHedgeCash = hedge.multiply(target).multiply(BigDecimal.ONE.add(fee)).negate();
        } else {
            // Откупаем всё: закрываем шорт и открываем лонг. Потом продаём лонг.
            closeOldCash = quantity.multiply(entry).multiply(BigDecimal.ONE.add(fee)).negate();
            openHedgeCash = hedge.multiply(entry).multiply(BigDecimal.ONE.add(fee)).negate();
            closeHedgeCash = hedge.multiply(target).multiply(BigDecimal.ONE.subtract(fee));
        }

        BigDecimal carryCost = hedge.multiply(entry).multiply(carry).multiply(BigDecimal.valueOf(days));

        // Итог = (деньги закрытия старой позиции − её себестоимость)
        //        + деньги открытия плеча + деньги его закрытия − перенос
        BigDecimal total = closeOldCash.subtract(costBasis)
                .add(openHedgeCash)
                .add(closeHedgeCash)
                .subtract(carryCost);

        assertThat(total.setScale(6, RoundingMode.HALF_UP))
                .as("по цене безубытка эпизод обязан сойтись в ноль")
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP));
    }
}
