package ru.larionov.backend.strategy.grid;

import ru.larionov.backend.exchange.api.model.FeeInfo;

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

    public record Economics(
            BigDecimal effectiveStep,
            BigDecimal stepRate,
            BigDecimal buyFeeRate,
            BigDecimal sellFeeRate,
            BigDecimal roundTripFeeRate,
            BigDecimal requiredStepRate,
            BigDecimal commissionCoverageRatio,
            BigDecimal netPerCycleRate,
            BigDecimal worstCaseCapital,
            GridSizing sizing
    ) {
    }

    private GridValidator() {
    }

    /*
     * Публичных входа ровно два: validate(...) с рабочим бюджетом и revalidate(...)
     * с уже посчитанным размером заявки. Две перегрузки ниже — сокращения для ручного
     * диапазона, они существуют ради тестов и вызывающего кода без бюджета.
     * Новых перегрузок сюда лучше не добавлять.
     */

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
        validate(cfg, GridRange.manual(cfg, null), ladder, priceIncrement,
                new FeeInfo(commissionRate, commissionRate), lotSize, maxCapital,
                cfg.workingBudget(BigDecimal.ZERO));
    }

    /**
     * Проверяет сетку с учётом комиссии покупки и продажи отдельно.
     *
     * Для пассивной grid-стратегии используем maker-ставки: покупка стоит
     * makerBuyRate, закрывающая продажа — makerSellRate. У T-Invest они совпадают,
     * но для других бирж это не обязано быть правдой.
     */
    public static void validate(GridConfig cfg,
                                GridLadder ladder,
                                BigDecimal priceIncrement,
                                FeeInfo fees,
                                int lotSize,
                                BigDecimal maxCapital) {
        validate(cfg, GridRange.manual(cfg, null), ladder, priceIncrement, fees, lotSize, maxCapital,
                cfg.workingBudget(BigDecimal.ZERO));
    }

    /**
     * Канонический вход: сам считает размер заявки.
     *
     * @param workingBudget рабочий бюджет бота; игнорируется в режиме FIXED_LOTS
     */
    public static Economics validate(GridConfig cfg,
                                     GridRange range,
                                     GridLadder ladder,
                                     BigDecimal priceIncrement,
                                     FeeInfo fees,
                                     int lotSize,
                                     BigDecimal maxCapital,
                                     BigDecimal workingBudget) {

        GridSizing sizing = cfg.budgetSized()
                ? GridSizing.fromBudget(cfg, ladder, lotSize, workingBudget)
                : GridSizing.fixed(cfg.lotsPerOrder(), ladder, lotSize);

        return check(cfg, range, ladder, priceIncrement, fees, maxCapital, sizing);
    }

    /**
     * Повторная проверка с УЖЕ посчитанным размером заявки.
     *
     * Нужна на пути обновления комиссий: пересчитывать размер там нельзя, иначе
     * у бота с реинвестированием прибыли объём заявки менялся бы посреди жизни сетки,
     * между покупкой и её встречной продажей.
     */
    public static Economics revalidate(GridConfig cfg,
                                       GridRange range,
                                       GridLadder ladder,
                                       BigDecimal priceIncrement,
                                       FeeInfo fees,
                                       BigDecimal maxCapital,
                                       GridSizing frozenSizing) {
        return check(cfg, range, ladder, priceIncrement, fees, maxCapital, frozenSizing);
    }

    private static Economics check(GridConfig cfg,
                                   GridRange range,
                                   GridLadder ladder,
                                   BigDecimal priceIncrement,
                                   FeeInfo fees,
                                   BigDecimal maxCapital,
                                   GridSizing sizing) {

        BigDecimal step = ladder.effectiveStep();

        // После округления к шагу цены соседние уровни либо совпадают, либо отличаются
        // минимум на один шаг цены — поэтому отдельной проверки «шаг меньше шага цены»
        // не нужно: она недостижима. Нулевой шаг и означает слипшиеся уровни.
        if (step.signum() <= 0) {
            throw new IllegalStateException(
                    ("Уровни сетки слиплись: на диапазоне %s..%s при %d уровнях шаг оказался "
                            + "меньше шага цены инструмента (%s). Уменьшите число уровней "
                            + "или расширьте диапазон.")
                            .formatted(range.lower().toPlainString(), range.upper().toPlainString(),
                                    range.levels(),
                                    priceIncrement == null ? "?" : priceIncrement.toPlainString()));
        }

        // Сравниваем в процентах от цены: комиссия берётся с оборота, а не с шага.
        // Худший случай — верх диапазона: там шаг в процентах наименьший.
        BigDecimal referencePrice = range.upper();
        BigDecimal stepPct = step.divide(referencePrice, 9, RoundingMode.HALF_UP);

        FeeInfo feeInfo = fees == null ? new FeeInfo(BigDecimal.ZERO, BigDecimal.ZERO) : fees;
        BigDecimal buyFeePct = feeInfo.makerBuyRate();
        BigDecimal sellFeePct = feeInfo.makerSellRate();
        BigDecimal roundTripPct = feeInfo.makerRoundTripRate();
        BigDecimal required = roundTripPct.multiply(cfg.minStepToCommissionRatio());

        if (stepPct.compareTo(required) < 0) {
            throw new IllegalStateException(
                    ("Шаг сетки не окупает комиссию. Шаг %s (%s%% от цены %s), "
                            + "комиссия за оборот %s%% (покупка %s%% + продажа %s%%), требуется минимум %s%% "
                            + "(запас ×%s). Увеличьте диапазон, уменьшите число уровней "
                            + "или проверьте ставку комиссии в настройках подключения.")
                            .formatted(
                                    step.toPlainString(),
                                    pct(stepPct),
                                    referencePrice.toPlainString(),
                                    pct(roundTripPct),
                                    pct(buyFeePct),
                                    pct(sellFeePct),
                                    pct(required),
                                    cfg.minStepToCommissionRatio().toPlainString()));
        }

        // Худший случай: все уровни покупки выкуплены по своей цене.
        BigDecimal worstCase = sizing.worstCaseNotional();
        // В бюджетных режимах worstCase <= бюджета по построению, поэтому проверка ниже
        // может сработать только при maxCapital < budget — а это настоящая
        // рассогласованность двух настроек, и сказать о ней надо.
        if (maxCapital != null && maxCapital.signum() > 0) {
            if (worstCase.compareTo(maxCapital) > 0) {
                throw new IllegalStateException(
                        ("Сетка не помещается в лимит капитала: при полном выкупе всех уровней "
                                + "потребуется %s при потолке %s. Уменьшите число уровней, "
                                + "размер заявки или поднимите лимит.")
                                .formatted(worstCase.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                        maxCapital.toPlainString()));
            }
        }

        BigDecimal coverage = roundTripPct.signum() == 0
                ? null
                : stepPct.divide(roundTripPct, 9, RoundingMode.HALF_UP);
        return new Economics(
                step, stepPct, buyFeePct, sellFeePct, roundTripPct, required,
                coverage, stepPct.subtract(roundTripPct), worstCase, sizing);
    }

    private static String pct(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
