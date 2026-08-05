package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GridLadderTest {

    private static GridConfig cfg(String low, String high, int levels) {
        return new GridConfig(new BigDecimal(low), new BigDecimal(high), levels,
                1L, 10, null, null, true);
    }

    @Test
    void buildsInclusiveLadderFromLowToHigh() {
        GridLadder ladder = GridLadder.build(cfg("100", "110", 10), new BigDecimal("0.01"));

        assertThat(ladder.levelCount()).isEqualTo(10);
        assertThat(ladder.prices()).hasSize(11);
        assertThat(ladder.priceAt(0)).isEqualByComparingTo("100");
        assertThat(ladder.priceAt(10)).isEqualByComparingTo("110");
        assertThat(ladder.priceAt(5)).isEqualByComparingTo("105");
    }

    @Test
    void allPricesAreMultiplesOfPriceIncrement() {
        // Шаг цены 0.05 при «некруглом» диапазоне — биржа не примет цену не кратную ему.
        BigDecimal increment = new BigDecimal("0.05");
        GridLadder ladder = GridLadder.build(cfg("100.03", "107.77", 7), increment);

        for (BigDecimal price : ladder.prices()) {
            BigDecimal remainder = price.remainder(increment).abs();
            assertThat(remainder.compareTo(BigDecimal.ZERO))
                    .as("Цена %s не кратна шагу %s", price, increment)
                    .isZero();
        }
    }

    @Test
    void effectiveStepReflectsRoundingNotTheIdealStep() {
        // Идеальный шаг 0.333..., но при шаге цены 0.1 фактический шаг «плавает».
        GridLadder ladder = GridLadder.build(cfg("10", "11", 3), new BigDecimal("0.1"));

        // Сравнивать с комиссией нужно именно фактический — и притом минимальный шаг.
        assertThat(ladder.effectiveStep()).isLessThanOrEqualTo(new BigDecimal("0.4"));
        assertThat(ladder.effectiveStep()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void findsNearestLevelsAroundCurrentPrice() {
        GridLadder ladder = GridLadder.build(cfg("100", "110", 10), new BigDecimal("0.01"));

        assertThat(ladder.highestLevelBelow(new BigDecimal("104.5"))).isEqualTo(4);
        assertThat(ladder.lowestLevelAbove(new BigDecimal("104.5"))).isEqualTo(5);

        // Цена ровно на уровне: «строго ниже» и «строго выше» её не включают.
        assertThat(ladder.highestLevelBelow(new BigDecimal("105"))).isEqualTo(4);
        assertThat(ladder.lowestLevelAbove(new BigDecimal("105"))).isEqualTo(6);
    }

    @Test
    void reportsNoLevelWhenPriceIsOutsideTheRange() {
        GridLadder ladder = GridLadder.build(cfg("100", "110", 10), new BigDecimal("0.01"));

        assertThat(ladder.highestLevelBelow(new BigDecimal("99"))).isEqualTo(-1);
        assertThat(ladder.lowestLevelAbove(new BigDecimal("111"))).isEqualTo(-1);
    }
}
