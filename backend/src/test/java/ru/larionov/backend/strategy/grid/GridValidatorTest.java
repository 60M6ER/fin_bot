package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверка того самого фильтра, о который разбился арбитраж: если шаг сетки
 * не окупает комиссию за оборот, бот не должен стартовать вовсе.
 */
class GridValidatorTest {

    private static final BigDecimal INCREMENT = new BigDecimal("0.01");

    private static GridConfig cfg(String low, String high, int levels, String ratio) {
        return new GridConfig(new BigDecimal(low), new BigDecimal(high), levels,
                1L, 100, null, ratio == null ? null : new BigDecimal(ratio), true);
    }

    private static void validate(GridConfig cfg, String commissionRate, BigDecimal maxCapital) {
        GridLadder ladder = GridLadder.build(cfg, INCREMENT);
        GridValidator.validate(cfg, ladder, INCREMENT, new BigDecimal(commissionRate), 1, maxCapital);
    }

    @Test
    void acceptsGridWhoseStepComfortablyBeatsCommission() {
        // Шаг 1% при комиссии 0.05% за сторону (0.1% за оборот) — запас десятикратный.
        assertThatCode(() -> validate(cfg("100", "110", 10, null), "0.0005", null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsGridWhoseStepIsEatenByCommission() {
        // Шаг 0.1% при комиссии 0.3% за сторону (0.6% за оборот) — каждый цикл в минус.
        assertThatThrownBy(() -> validate(cfg("100", "101", 10, null), "0.003", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не окупает комиссию")
                // В сообщении должны быть цифры, чтобы было понятно, что менять.
                .hasMessageContaining("0.6000")
                .hasMessageContaining("Увеличьте диапазон");
    }

    @Test
    void rejectsGridThatOnlyBarelyCoversCommission() {
        // Шаг ровно равен комиссии за оборот: формально не убыток, но и не заработок.
        // Запас по умолчанию ×1.5 такую сетку не пропускает.
        assertThatThrownBy(() -> validate(cfg("100", "101", 10, null), "0.0005", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не окупает комиссию");
    }

    @Test
    void allowsTighterGridWhenUserLowersTheRequiredMargin() {
        // Тот же шаг проходит, если пользователь осознанно снизил требуемый запас.
        assertThatCode(() -> validate(cfg("100", "101", 10, "0.5"), "0.0005", null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsGridWhoseLevelsCollapseIntoEachOther() {
        // 1000 уровней на диапазоне в 1 рубль при шаге цены 0.01: после округления
        // соседние уровни получают одну и ту же цену, и сетки просто нет.
        assertThatThrownBy(() -> validate(cfg("100", "101", 1000, null), "0.0", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("слиплись")
                .hasMessageContaining("Уменьшите число уровней");
    }

    @Test
    void rejectsGridThatDoesNotFitTheCapitalLimit() {
        // 10 уровней по ~100 рублей = ~1000 при потолке 500.
        assertThatThrownBy(() -> validate(cfg("100", "110", 10, null), "0.0005", new BigDecimal("500")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не помещается в лимит капитала");
    }

    @Test
    void acceptsGridThatFitsTheCapitalLimit() {
        assertThatCode(() -> validate(cfg("100", "110", 10, null), "0.0005", new BigDecimal("5000")))
                .doesNotThrowAnyException();
    }

    @Test
    void usesTheWorstCaseEndOfTheRangeForThePercentageCheck() {
        // Шаг в процентах наименьший у верхней границы — именно там сетка
        // перестаёт окупаться первой, и проверять надо по ней.
        GridConfig wide = cfg("10", "1000", 99, null);
        assertThatCode(() -> validate(wide, "0.0005", null)).doesNotThrowAnyException();

        // Тот же шаг в абсолюте, но цены выше — процент падает, и сетка отвергается.
        GridConfig high = cfg("10000", "10990", 99, null);
        assertThatThrownBy(() -> validate(high, "0.003", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
