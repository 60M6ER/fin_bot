package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Подбор размера заявки под бюджет.
 *
 * Главное свойство, ради которого этот класс существует: бюджет обеспечен
 * ПО ПОСТРОЕНИЮ. Полный выкуп всех уровней покупки не может стоить дороже бюджета,
 * и это проверяется в каждом тесте, а не только в отдельном.
 */
class GridSizingTest {

    private static final BigDecimal INCREMENT = new BigDecimal("0.01");

    private static GridConfig budgetCfg(String budget, GridConfig.SizingMode mode) {
        return new GridConfig(
                new BigDecimal("100"), new BigDecimal("110"), 10, null, 10,
                null, null, null, true,
                false, null, null, null, null, null,
                null, null, null, null, null, null,
                budget == null ? null : new BigDecimal(budget), mode, null);
    }

    private static GridLadder ladder() {
        return GridLadder.build(budgetCfg("1", GridConfig.SizingMode.UNIFORM), INCREMENT);
    }

    private static final BigDecimal STEP_1 = BigDecimal.ONE;
    private static final BigDecimal STEP_10 = BigDecimal.TEN;

    /** Сумма цен всех уровней ПОКУПКИ (0..N-1), умноженная на шаг количества. */
    private static BigDecimal denominator(GridLadder ladder, BigDecimal step) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < ladder.levelCount(); i++) {
            sum = sum.add(ladder.priceAt(i).multiply(step));
        }
        return sum;
    }

    // ==============================
    // UNIFORM
    // ==============================

    @Test
    void uniformGivesTheSameSizeOnEveryLevelAndFitsTheBudget() {
        GridLadder ladder = ladder();
        BigDecimal budget = new BigDecimal("10000");

        GridSizing sizing = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, STEP_1, budget);

        assertThat(sizing.uniform()).isTrue();
        assertThat(sizing.quantityByLevel()).hasSize(ladder.levelCount());
        assertThat(sizing.worstCaseNotional()).isLessThanOrEqualTo(budget);
        assertThat(sizing.budgetLeftover()).isEqualByComparingTo(
                budget.subtract(sizing.worstCaseNotional()));
    }

    /**
     * Самое важное утверждение всего изменения: подбор размера — это ТОЧНАЯ инверсия
     * расчёта «худшего случая» в GridValidator. Если скормить бюджетом ровно ту сумму,
     * которую требует фиксированный размер, получиться должен ровно он же.
     */
    @Test
    void uniformIsTheExactInverseOfTheWorstCaseCalculation() {
        GridLadder ladder = ladder();
        BigDecimal quantityPerOrder = new BigDecimal("7");

        GridSizing fixed = GridSizing.fixed(quantityPerOrder, ladder, STEP_1);
        GridSizing derived = GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, STEP_1, fixed.worstCaseNotional());

        assertThat(derived.quantityAt(0)).isEqualByComparingTo(quantityPerOrder);
        assertThat(derived.worstCaseNotional()).isEqualByComparingTo(fixed.worstCaseNotional());
        assertThat(derived.budgetLeftover()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Шаг количества огрубляет размер заявки вниз до кратного себе — и никогда вверх,
     * иначе полный выкуп вышел бы за бюджет.
     */
    @Test
    void uniformRoundsSizeDownToTheQuantityStep() {
        GridLadder ladder = ladder();
        BigDecimal budget = new BigDecimal("200000");
        GridConfig cfg = budgetCfg("200000", GridConfig.SizingMode.UNIFORM);

        GridSizing one = GridSizing.fromBudget(cfg, ladder, STEP_1, budget);
        GridSizing ten = GridSizing.fromBudget(cfg, ladder, STEP_10, budget);

        assertThat(ten.quantityAt(0).remainder(STEP_10)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ten.quantityAt(0)).isLessThanOrEqualTo(one.quantityAt(0));
        assertThat(ten.worstCaseNotional()).isLessThanOrEqualTo(budget);
        assertThat(one.worstCaseNotional()).isLessThanOrEqualTo(budget);
    }

    /** Дробный шаг: тот же расчёт, только вниз округляем до 0.000001. */
    @Test
    void uniformSupportsFractionalQuantityStep() {
        GridLadder ladder = ladder();
        BigDecimal step = new BigDecimal("0.000001");
        BigDecimal budget = new BigDecimal("1000");

        GridSizing sizing = GridSizing.fromBudget(
                budgetCfg("1000", GridConfig.SizingMode.UNIFORM), ladder, step, budget);

        assertThat(sizing.quantityAt(0).remainder(step)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(sizing.worstCaseNotional()).isLessThanOrEqualTo(budget);
    }

    @Test
    void refusesWhenTheQuantityStepMakesTheGridUnaffordable() {
        GridLadder ladder = ladder();
        // 10 000 хватает при шаге 1, но не при шаге 10 — и об этом надо сказать.
        assertThatThrownBy(() -> GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, STEP_10, new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не хватает даже на минимальный шаг");
    }

    // ==============================
    // PER_LEVEL
    // ==============================

    @Test
    void perLevelSpreadsMoneyEvenlyAndNeverGrowsWithPrice() {
        GridLadder ladder = ladder();
        BigDecimal budget = new BigDecimal("10000");
        int levels = ladder.levelCount();

        GridSizing sizing = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.PER_LEVEL), ladder, STEP_1, budget);

        assertThat(sizing.worstCaseNotional()).isLessThanOrEqualTo(budget);

        BigDecimal perLevel = budget.divide(BigDecimal.valueOf(levels), 9, java.math.RoundingMode.DOWN);
        BigDecimal previous = null;
        for (int i = 0; i < levels; i++) {
            BigDecimal quantity = sizing.quantityAt(i);
            assertThat(quantity).isPositive();
            // Цены растут — количество не может расти.
            if (previous != null) {
                assertThat(quantity).isLessThanOrEqualTo(previous);
            }
            previous = quantity;

            // Трата на уровне не больше своей доли и не меньше неё на один шаг.
            BigDecimal cost = ladder.priceAt(i);
            BigDecimal spent = cost.multiply(quantity);
            assertThat(spent).isLessThanOrEqualTo(perLevel);
            assertThat(spent.add(cost.multiply(STEP_1))).isGreaterThan(perLevel);
        }
    }

    @Test
    void perLevelUsesBudgetAtLeastAsWellAsUniform() {
        GridLadder ladder = ladder();
        BigDecimal budget = new BigDecimal("10000");

        GridSizing uniform = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, STEP_1, budget);
        GridSizing perLevel = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.PER_LEVEL), ladder, STEP_1, budget);

        assertThat(perLevel.worstCaseNotional())
                .isGreaterThanOrEqualTo(uniform.worstCaseNotional());
    }

    // ==============================
    // ВЕРХНИЙ УРОВЕНЬ
    // ==============================

    @Test
    void topLevelIsFundedOnlyInFixedMode() {
        GridLadder ladder = ladder();
        int top = ladder.levelCount();

        // FIXED_QUANTITY обязан вести себя как раньше: placeMissingBuys умеет поставить
        // покупку на верхний уровень, когда цена ушла выше диапазона.
        assertThat(GridSizing.fixed(new BigDecimal("3"), ladder, STEP_1).quantityAt(top))
                .isEqualByComparingTo("3");

        // В бюджетных режимах верхний уровень продажный: встречной продажи для него нет.
        GridSizing budgeted = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, STEP_1, new BigDecimal("10000"));
        assertThat(budgeted.quantityAt(top)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ==============================
    // ВЫРОЖДЕННЫЕ СЛУЧАИ
    // ==============================

    @Test
    void uniformRefusesWhenBudgetCannotFundOneStepPerLevel() {
        GridLadder ladder = ladder();
        BigDecimal minimum = denominator(ladder, STEP_1);

        assertThatThrownBy(() -> GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, STEP_1,
                minimum.subtract(BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не хватает даже на минимальный шаг")
                // Сообщение обязано называть требуемый минимум: иначе пользователь
                // не знает, до какой суммы поднимать бюджет.
                .hasMessageContaining(minimum.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    void uniformAcceptsBudgetExactlyAtTheMinimum() {
        GridLadder ladder = ladder();

        GridSizing sizing = GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, STEP_1, denominator(ladder, STEP_1));

        assertThat(sizing.quantityAt(0)).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void perLevelRefusesWhenTheHighestBuyLevelIsUnfunded() {
        GridLadder ladder = ladder();
        int top = ladder.levelCount() - 1;
        // Хватает на нижние уровни, но не на верхний — сетка с дырой наверху
        // молча изменила бы форму стратегии, поэтому падаем.
        BigDecimal budget = ladder.priceAt(top)
                .multiply(BigDecimal.valueOf(ladder.levelCount()))
                .subtract(BigDecimal.ONE);

        assertThatThrownBy(() -> GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.PER_LEVEL), ladder, STEP_1, budget))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уровень " + top);
    }

    @Test
    void refusesExhaustedBudget() {
        GridLadder ladder = ladder();
        GridConfig cfg = budgetCfg("1", GridConfig.SizingMode.UNIFORM);

        // Достижимо только при реинвестировании прибыли: убыток съел бюджет.
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, STEP_1, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, STEP_1, new BigDecimal("-500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, STEP_1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
    }
}
