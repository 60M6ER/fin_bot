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

    /** Сумма цен всех уровней ПОКУПКИ (0..N-1), умноженная на лотность. */
    private static BigDecimal denominator(GridLadder ladder, int lotSize) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < ladder.levelCount(); i++) {
            sum = sum.add(ladder.priceAt(i).multiply(BigDecimal.valueOf(lotSize)));
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
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, 1, budget);

        assertThat(sizing.uniform()).isTrue();
        assertThat(sizing.lotsByLevel()).hasSize(ladder.levelCount());
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
        long lotsPerOrder = 7L;

        GridSizing fixed = GridSizing.fixed(lotsPerOrder, ladder, 1);
        GridSizing derived = GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, 1, fixed.worstCaseNotional());

        assertThat(derived.lotsAt(0)).isEqualTo(lotsPerOrder);
        assertThat(derived.worstCaseNotional()).isEqualByComparingTo(fixed.worstCaseNotional());
        assertThat(derived.budgetLeftover()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void uniformAccountsForLotSize() {
        GridLadder ladder = ladder();
        // Бюджета должно хватать на обе лотности: при лоте 10 вся сетка стоит вдесятеро
        // дороже, и на 10 000 она бы просто не профинансировалась.
        BigDecimal budget = new BigDecimal("200000");
        GridConfig cfg = budgetCfg("200000", GridConfig.SizingMode.UNIFORM);

        GridSizing one = GridSizing.fromBudget(cfg, ladder, 1, budget);
        GridSizing ten = GridSizing.fromBudget(cfg, ladder, 10, budget);

        // Лот вдесятеро дороже — лотов помещается вдесятеро меньше.
        // Вложенное округление вниз здесь тождество: floor(floor(B/D)/10) == floor(B/10D).
        assertThat(ten.lotsAt(0)).isEqualTo(one.lotsAt(0) / 10);
        assertThat(ten.worstCaseNotional()).isLessThanOrEqualTo(budget);
        assertThat(one.worstCaseNotional()).isLessThanOrEqualTo(budget);
    }

    @Test
    void refusesWhenLotSizeMakesTheGridUnaffordable() {
        GridLadder ladder = ladder();
        // 10 000 хватает при лоте 1, но не при лоте 10 — и об этом надо сказать.
        assertThatThrownBy(() -> GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, 10, new BigDecimal("10000")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не хватает даже на один лот");
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
                budgetCfg("10000", GridConfig.SizingMode.PER_LEVEL), ladder, 1, budget);

        assertThat(sizing.worstCaseNotional()).isLessThanOrEqualTo(budget);

        BigDecimal perLevel = budget.divide(BigDecimal.valueOf(levels), 9, java.math.RoundingMode.DOWN);
        long previous = Long.MAX_VALUE;
        for (int i = 0; i < levels; i++) {
            long lots = sizing.lotsAt(i);
            assertThat(lots).isPositive();
            // Цены растут — число лотов не может расти.
            assertThat(lots).isLessThanOrEqualTo(previous);
            previous = lots;

            // Трата на уровне не больше своей доли и не меньше неё на целый лот.
            BigDecimal cost = ladder.priceAt(i);
            BigDecimal spent = cost.multiply(BigDecimal.valueOf(lots));
            assertThat(spent).isLessThanOrEqualTo(perLevel);
            assertThat(spent.add(cost)).isGreaterThan(perLevel);
        }
    }

    @Test
    void perLevelUsesBudgetAtLeastAsWellAsUniform() {
        GridLadder ladder = ladder();
        BigDecimal budget = new BigDecimal("10000");

        GridSizing uniform = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, 1, budget);
        GridSizing perLevel = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.PER_LEVEL), ladder, 1, budget);

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

        // FIXED_LOTS обязан вести себя как раньше: placeMissingBuys умеет поставить
        // покупку на верхний уровень, когда цена ушла выше диапазона.
        assertThat(GridSizing.fixed(3L, ladder, 1).lotsAt(top)).isEqualTo(3L);

        // В бюджетных режимах верхний уровень продажный: встречной продажи для него нет.
        GridSizing budgeted = GridSizing.fromBudget(
                budgetCfg("10000", GridConfig.SizingMode.UNIFORM), ladder, 1, new BigDecimal("10000"));
        assertThat(budgeted.lotsAt(top)).isZero();
    }

    // ==============================
    // ВЫРОЖДЕННЫЕ СЛУЧАИ
    // ==============================

    @Test
    void uniformRefusesWhenBudgetCannotFundOneLotPerLevel() {
        GridLadder ladder = ladder();
        BigDecimal minimum = denominator(ladder, 1);

        assertThatThrownBy(() -> GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, 1,
                minimum.subtract(BigDecimal.ONE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не хватает даже на один лот")
                // Сообщение обязано называть требуемый минимум: иначе пользователь
                // не знает, до какой суммы поднимать бюджет.
                .hasMessageContaining(minimum.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    @Test
    void uniformAcceptsBudgetExactlyAtTheMinimum() {
        GridLadder ladder = ladder();

        GridSizing sizing = GridSizing.fromBudget(
                budgetCfg("1", GridConfig.SizingMode.UNIFORM), ladder, 1, denominator(ladder, 1));

        assertThat(sizing.lotsAt(0)).isEqualTo(1L);
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
                budgetCfg("1", GridConfig.SizingMode.PER_LEVEL), ladder, 1, budget))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("уровень " + top);
    }

    @Test
    void refusesExhaustedBudget() {
        GridLadder ladder = ladder();
        GridConfig cfg = budgetCfg("1", GridConfig.SizingMode.UNIFORM);

        // Достижимо только при реинвестировании прибыли: убыток съел бюджет.
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, 1, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, 1, new BigDecimal("-500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
        assertThatThrownBy(() -> GridSizing.fromBudget(cfg, ladder, 1, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Рабочий бюджет исчерпан");
    }
}
