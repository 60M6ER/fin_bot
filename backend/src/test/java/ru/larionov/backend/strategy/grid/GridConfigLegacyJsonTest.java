package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Конфигурация боевого бота, записанная ДО появления трейлинга.
 *
 * Такой JSON лежит в базе у каждого работающего бота, и после обновления он обязан
 * означать ровно то же, что означал. Проверка стоит отдельным тестом, потому что цена
 * ошибки здесь — не падение, а тихая смена поведения на реальных деньгах: новое поле
 * с неудачным умолчанием изменило бы способ выхода из плеча у бота, которого никто
 * не трогал.
 *
 * JSON — снимок настроек боевого MAGN GRID на 14.08.2026.
 */
class GridConfigLegacyJsonTest {

    private static final String LIVE_CONFIG = """
            {
              "dryRun": false,
              "levels": 12,
              "enabled": true,
              "autoRange": true,
              "atrPeriods": 24,
              "atrInterval": "H1",
              "onRangeExit": "REPLACE_LOWER",
              "atrMultiplier": 2,
              "instrumentUid": "7132b1c9-ee26-4464-b5b5-1046264b61d9",
              "maxActiveOrders": 10,
              "maxHalfWidthPct": 0.20,
              "minHalfWidthPct": 0.01,
              "maxRealizedLoss": 500,
              "onUpperBreakout": "REPLACE_UPPER",
              "breakoutMarginPct": 0.002,
              "breakoutConfirmSeconds": 300,
              "replaceCooldownSeconds": 1200,
              "maxDownwardReplacements": 10,
              "minStepToCommissionRatio": 1.5,
              "budget": 6400,
              "sizingMode": "UNIFORM",
              "profitPolicy": "COMPOUND",
              "direction": "LONG",
              "marginEnabled": true,
              "expectedCycleDays": 10,
              "onAdverseBreakout": "HEDGE_AND_RECOVER",
              "hedgeMultiplier": 4,
              "maxHedgeEpisodes": 1,
              "maxHedgeHoldDays": 10,
              "hedgeStopLossPct": 0.02,
              "hedgeAndGridConcurrent": true
            }
            """;

    @Test
    @DisplayName("конфиг без полей трейлинга читается и сохраняет прежнее поведение")
    void legacyConfigKeepsItsMeaning() {
        GridConfig cfg = new ObjectMapper().readValue(LIVE_CONFIG, GridConfig.class);

        assertThat(cfg.hedgeExitMode())
                .as("выход из плеча остаётся прежним — по расчётному безубытку заявкой в стакане")
                .isEqualTo(GridConfig.HedgeExitMode.BREAKEVEN_TARGET);
        assertThat(cfg.hedgeTrailingOffsetPct())
                .as("отступ подставлен, но без режима он ни на что не влияет")
                .isEqualByComparingTo("0.005");

        // Остальное — контрольные точки того, что разбор не съехал по составу полей.
        assertThat(cfg.levels()).isEqualTo(12);
        assertThat(cfg.onRangeExit()).isEqualTo(GridConfig.RangeExitAction.REPLACE_LOWER);
        assertThat(cfg.onAdverseBreakout())
                .isEqualTo(GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER);
        assertThat(cfg.hedgeMultiplier()).isEqualByComparingTo("4");
        assertThat(cfg.maxRealizedLoss()).isEqualByComparingTo("500");
        assertThat(cfg.breakoutMarginPct()).isEqualByComparingTo("0.002");
        assertThat(cfg.replaceCooldownSeconds()).isEqualTo(1200);
        assertThat(cfg.budget()).isEqualByComparingTo("6400");
        assertThat(cfg.sizingMode()).isEqualTo(GridConfig.SizingMode.UNIFORM);
        assertThat(cfg.profitPolicy()).isEqualTo(GridConfig.ProfitPolicy.COMPOUND);
        assertThat(cfg.flipDirectionOnAdverse())
                .as("разворот сетки в шорт включается маржой — так было и до обновления")
                .isTrue();
    }

    /**
     * Порог пробоя боевого поколения 8 — числами, которые видел человек.
     *
     * Диапазон 20.5079–21.3821: запас 0.2% (0.0410) больше полушага (0.035), значит
     * порогом становится он. Между границей и порогом лежит полоса шириной четыре
     * копейки, где цена уже вне сетки, но пробоем это ещё не считается: 20.49 — там,
     * а 20.445 и 20.41 — уже пробой. Числа зафиксированы тестом потому, что на глаз
     * эта разница не читается: 20.445 выглядит «чуть ниже 20.4669», а на деле ниже.
     */
    @Test
    @DisplayName("порог пробоя считается по большему из запаса и полушага")
    void breakoutThresholdTakesTheWiderOfMarginAndHalfStep() {
        GridRange range = new GridRange(new BigDecimal("20.5079"), new BigDecimal("21.3821"), 12,
                GridRange.Origin.ATR_REPLACED_DOWN, java.time.Instant.parse("2026-08-14T09:31:11Z"));
        GridLadder ladder = GridLadder.build(range, new BigDecimal("0.005"));

        assertThat(ladder.priceAt(0)).isEqualByComparingTo("20.51");
        assertThat(ladder.effectiveStep()).isEqualByComparingTo("0.07");

        BigDecimal margin = range.lower().multiply(new BigDecimal("0.002"))
                .max(ladder.effectiveStep().divide(BigDecimal.valueOf(2)));
        BigDecimal threshold = GridDirection.LONG.adverseThreshold(range.lower(), margin);

        assertThat(margin).isEqualByComparingTo("0.0410158");
        assertThat(threshold).isEqualByComparingTo("20.4668842");
        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("20.49"), range.lower()))
                .as("20.49 — уже вне диапазона: покупать бот там не станет")
                .isTrue();
        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("20.49"), threshold))
                .as("но до порога не дошло — это та самая молчаливая полоса")
                .isFalse();

        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("20.445"), threshold))
                .as("20.445 порог УЖЕ пройден — подтверждение обязано начаться")
                .isTrue();
        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("20.41"), threshold))
                .as("тем более 20.41")
                .isTrue();
    }
}
