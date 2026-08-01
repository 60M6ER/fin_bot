package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Проверка, что сетка вообще может зарабатывать.
 *
 * Это тот самый фильтр, о который разбился арбитраж, только сделанный механическим:
 * если шаг сетки не окупает комиссию за оборот, стратегия обречена на убыток
 * независимо от того, как ходит рынок. Такой бот не должен стартовать вовсе —
 * молча торговать в минус хуже, чем не торговать.
 */
public final class GridValidator {

    private GridValidator() {
    }

    /**
     * @param commissionRate ставка за ОДНУ сторону сделки (0.0005 = 0.05%)
     * @param maxCapital     потолок капитала бота, null — без ограничения
     * @throws IllegalStateException с человекочитаемым расчётом, если сетка невыгодна
     */
    public static void validate(GridConfig cfg,
                                GridLadder ladder,
                                BigDecimal priceIncrement,
                                BigDecimal commissionRate,
                                int lotSize,
                                BigDecimal maxCapital) {

        BigDecimal step = ladder.effectiveStep();

        // После округления к шагу цены соседние уровни либо совпадают, либо отличаются
        // минимум на один шаг цены — поэтому отдельной проверки «шаг меньше шага цены»
        // не нужно: она недостижима. Нулевой шаг и означает слипшиеся уровни.
        if (step.signum() <= 0) {
            throw new IllegalStateException(
                    ("Уровни сетки слиплись: на диапазоне %s..%s при %d уровнях шаг оказался "
                            + "меньше шага цены инструмента (%s). Уменьшите число уровней "
                            + "или расширьте диапазон.")
                            .formatted(cfg.lowerPrice().toPlainString(), cfg.upperPrice().toPlainString(),
                                    cfg.levels(),
                                    priceIncrement == null ? "?" : priceIncrement.toPlainString()));
        }

        // Сравниваем в процентах от цены: комиссия берётся с оборота, а не с шага.
        // Худший случай — верх диапазона: там шаг в процентах наименьший.
        BigDecimal referencePrice = cfg.upperPrice();
        BigDecimal stepPct = step.divide(referencePrice, 9, RoundingMode.HALF_UP);

        BigDecimal rate = commissionRate == null ? BigDecimal.ZERO : commissionRate;
        BigDecimal roundTripPct = rate.multiply(BigDecimal.valueOf(2));
        BigDecimal required = roundTripPct.multiply(cfg.minStepToCommissionRatio());

        if (stepPct.compareTo(required) < 0) {
            throw new IllegalStateException(
                    ("Шаг сетки не окупает комиссию. Шаг %s (%s%% от цены %s), "
                            + "комиссия за оборот %s%%, требуется минимум %s%% "
                            + "(запас ×%s). Увеличьте диапазон, уменьшите число уровней "
                            + "или проверьте ставку комиссии в настройках подключения.")
                            .formatted(
                                    step.toPlainString(),
                                    pct(stepPct),
                                    referencePrice.toPlainString(),
                                    pct(roundTripPct),
                                    pct(required),
                                    cfg.minStepToCommissionRatio().toPlainString()));
        }

        if (maxCapital != null && maxCapital.signum() > 0) {
            // Худший случай: все уровни выкуплены по своей цене.
            BigDecimal worstCase = BigDecimal.ZERO;
            for (int i = 0; i < ladder.levelCount(); i++) {
                worstCase = worstCase.add(ladder.priceAt(i)
                        .multiply(BigDecimal.valueOf(cfg.lotsPerOrder()))
                        .multiply(BigDecimal.valueOf(lotSize <= 0 ? 1 : lotSize)));
            }
            if (worstCase.compareTo(maxCapital) > 0) {
                throw new IllegalStateException(
                        ("Сетка не помещается в лимит капитала: при полном выкупе всех уровней "
                                + "потребуется %s при потолке %s. Уменьшите число уровней, "
                                + "размер заявки или поднимите лимит.")
                                .formatted(worstCase.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                        maxCapital.toPlainString()));
            }
        }
    }

    private static String pct(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
