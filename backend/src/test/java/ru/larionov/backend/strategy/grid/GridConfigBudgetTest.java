package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Бюджет, режим сайзинга и политика прибыли в конфигурации.
 *
 * Здесь же живёт главная регрессия этого изменения: боты, созданные ДО появления
 * бюджета, обязаны продолжать работать ровно как раньше.
 */
class GridConfigBudgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GridConfig parse(String json) {
        return MAPPER.readValue(json, GridConfig.class);
    }

    // ==============================
    // СОВМЕСТИМОСТЬ СО СТАРЫМИ БОТАМИ
    // ==============================

    @Test
    void legacyConfigWithoutBudgetKeepsFixedQuantityBehaviour() {
        GridConfig cfg = parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"quantityPerOrder":3}
                """);

        assertThat(cfg.sizingMode()).isEqualTo(GridConfig.SizingMode.FIXED_QUANTITY);
        assertThat(cfg.budgetSized()).isFalse();
        assertThat(cfg.quantityPerOrder()).isEqualByComparingTo("3");
        assertThat(cfg.budget()).isNull();
        // Бюджета нет — рабочего бюджета тоже нет, и это не ошибка.
        assertThat(cfg.workingBudget(() -> new BigDecimal("500"))).isNull();
    }

    @Test
    void budgetWithoutExplicitModeDefaultsToUniform() {
        GridConfig cfg = parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"budget":10000}
                """);

        assertThat(cfg.sizingMode()).isEqualTo(GridConfig.SizingMode.UNIFORM);
        assertThat(cfg.budgetSized()).isTrue();
        // Намеренно null: любое забытое чтение обязано упасть, а не торговать одной штукой.
        assertThat(cfg.quantityPerOrder()).isNull();
    }

    @Test
    void profitPolicyDefaultsToWithdraw() {
        GridConfig cfg = parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"budget":10000}
                """);
        assertThat(cfg.profitPolicy()).isEqualTo(GridConfig.ProfitPolicy.WITHDRAW);
    }

    // ==============================
    // ВАЛИДАЦИЯ
    // ==============================

    @Test
    void fixedQuantityStillRequiresQuantityPerOrder() {
        assertThatThrownBy(() -> parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10}
                """))
                .hasMessageContaining("quantityPerOrder обязателен");
    }

    @Test
    void budgetModesRequireAPositiveBudget() {
        assertThatThrownBy(() -> parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"sizingMode":"PER_LEVEL"}
                """))
                .hasMessageContaining("budget обязателен");

        assertThatThrownBy(() -> parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"sizingMode":"UNIFORM","budget":0}
                """))
                .hasMessageContaining("budget обязателен");
    }

    // ==============================
    // РАБОЧИЙ БЮДЖЕТ
    // ==============================

    @Test
    void compoundAddsRealizedPnlToTheBudget() {
        GridConfig cfg = parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"budget":10000,
                 "sizingMode":"UNIFORM","profitPolicy":"COMPOUND"}
                """);

        assertThat(cfg.workingBudget(() -> new BigDecimal("750"))).isEqualByComparingTo("10750");
        // Убыток тоже реинвестируется — рабочий бюджет честно уменьшается.
        assertThat(cfg.workingBudget(() -> new BigDecimal("-2000"))).isEqualByComparingTo("8000");
        assertThat(cfg.workingBudget(() -> null)).isEqualByComparingTo("10000");
        assertThat(cfg.withdrawnProfit(new BigDecimal("750"))).isEqualByComparingTo("0");
    }

    @Test
    void withdrawKeepsTheBudgetFixedAndReportsProfitSeparately() {
        GridConfig cfg = parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"budget":10000,
                 "sizingMode":"UNIFORM","profitPolicy":"WITHDRAW"}
                """);

        assertThat(cfg.workingBudget(() -> new BigDecimal("750"))).isEqualByComparingTo("10000");
        assertThat(cfg.workingBudget(() -> new BigDecimal("-2000"))).isEqualByComparingTo("10000");
        assertThat(cfg.withdrawnProfit(new BigDecimal("750"))).isEqualByComparingTo("750");
    }

    @Test
    void survivesJsonRoundTrip() {
        String json = """
                {"lowerPrice":100,"upperPrice":110,"levels":10,"budget":10000,
                 "sizingMode":"PER_LEVEL","profitPolicy":"COMPOUND"}
                """;

        GridConfig once = parse(json);
        GridConfig twice = parse(MAPPER.writeValueAsString(once));

        assertThat(twice.budget()).isEqualByComparingTo(once.budget());
        assertThat(twice.sizingMode()).isEqualTo(once.sizingMode());
        assertThat(twice.profitPolicy()).isEqualTo(once.profitPolicy());
    }

    @Test
    void unknownFieldsAreStillIgnored() {
        // Один и тот же JSON читают и движок, и стратегия.
        assertThatCode(() -> parse("""
                {"lowerPrice":100,"upperPrice":110,"levels":10,"quantityPerOrder":1,
                 "instrumentUid":"uid","maxCapital":5000,"dryRun":true}
                """)).doesNotThrowAnyException();
    }
}
