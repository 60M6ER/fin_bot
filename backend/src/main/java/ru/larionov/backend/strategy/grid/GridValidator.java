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
                                BigDecimal quantityStep,
                                BigDecimal maxCapital) {
        validate(cfg, GridRange.manual(cfg, null), ladder, priceIncrement,
                new FeeInfo(commissionRate, commissionRate), quantityStep, maxCapital,
                cfg.workingBudget(() -> BigDecimal.ZERO));
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
                                BigDecimal quantityStep,
                                BigDecimal maxCapital) {
        validate(cfg, GridRange.manual(cfg, null), ladder, priceIncrement, fees, quantityStep, maxCapital,
                cfg.workingBudget(() -> BigDecimal.ZERO));
    }

    /**
     * Канонический вход: сам считает размер заявки.
     *
     * @param workingBudget рабочий бюджет бота; игнорируется в режиме FIXED_QUANTITY
     */
    public static Economics validate(GridConfig cfg,
                                     GridRange range,
                                     GridLadder ladder,
                                     BigDecimal priceIncrement,
                                     FeeInfo fees,
                                     BigDecimal quantityStep,
                                     BigDecimal maxCapital,
                                     BigDecimal workingBudget) {
        return validate(cfg, range, ladder, priceIncrement, fees, quantityStep, maxCapital,
                workingBudget, null);
    }

    /**
     * @param carryDailyRate суточная ставка переноса непокрытой позиции с подключения;
     *                       null для лонговой сетки, которой переносить нечего
     */
    public static Economics validate(GridConfig cfg,
                                     GridRange range,
                                     GridLadder ladder,
                                     BigDecimal priceIncrement,
                                     FeeInfo fees,
                                     BigDecimal quantityStep,
                                     BigDecimal maxCapital,
                                     BigDecimal workingBudget,
                                     BigDecimal carryDailyRate) {

        GridSizing sizing = cfg.budgetSized()
                ? GridSizing.fromBudget(cfg, ladder, quantityStep, workingBudget)
                : GridSizing.fixed(cfg.quantityPerOrder(), ladder, quantityStep);

        return check(cfg, range, ladder, priceIncrement, fees, maxCapital, sizing, carryDailyRate);
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
        return revalidate(cfg, range, ladder, priceIncrement, fees, maxCapital, frozenSizing, null);
    }

    public static Economics revalidate(GridConfig cfg,
                                       GridRange range,
                                       GridLadder ladder,
                                       BigDecimal priceIncrement,
                                       FeeInfo fees,
                                       BigDecimal maxCapital,
                                       GridSizing frozenSizing,
                                       BigDecimal carryDailyRate) {
        return check(cfg, range, ladder, priceIncrement, fees, maxCapital, frozenSizing, carryDailyRate);
    }

    private static Economics check(GridConfig cfg,
                                   GridRange range,
                                   GridLadder ladder,
                                   BigDecimal priceIncrement,
                                   FeeInfo fees,
                                   BigDecimal maxCapital,
                                   GridSizing sizing,
                                   BigDecimal carryDailyRate) {

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

        // Шортовая сетка платит не только комиссию, но и за само удержание позиции:
        // каждый её незакрытый цикл — это непокрытая позиция, которую брокер тарифицирует
        // посуточно. Сетка, чей цикл занимает двое суток, обязана окупать и эти двое суток,
        // иначе она убыточна по построению — ровно то, ради чего этот валидатор написан.
        BigDecimal carryPct = carryPerCycleRate(cfg, carryDailyRate);
        BigDecimal required = roundTripPct.multiply(cfg.minStepToCommissionRatio()).add(carryPct);

        if (stepPct.compareTo(required) < 0) {
            // Формулировка по существу: у лонговой сетки издержка ровно одна — комиссия,
            // и называть её обобщённо значило бы отвечать расплывчатее, чем знаем.
            throw new IllegalStateException(
                    ("Шаг сетки не окупает %s. Шаг %s (%s%% от цены %s), "
                            + "комиссия за оборот %s%% (покупка %s%% + продажа %s%%)%s, "
                            + "требуется минимум %s%% (запас ×%s). Увеличьте диапазон, "
                            + "уменьшите число уровней или проверьте ставки в настройках подключения.")
                            .formatted(
                                    carryPct.signum() > 0 ? "издержки" : "комиссию",
                                    step.toPlainString(),
                                    pct(stepPct),
                                    referencePrice.toPlainString(),
                                    pct(roundTripPct),
                                    pct(buyFeePct),
                                    pct(sellFeePct),
                                    carryPct.signum() > 0
                                            ? ", перенос за цикл %s%%".formatted(pct(carryPct))
                                            : "",
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

    /**
     * Во сколько обходится удержание позиции за один цикл сетки, долей от цены.
     *
     * Ноль для лонга: длинная позиция покрыта, за её удержание брокер не берёт ничего.
     * Для шорта — суточная ставка подключения, умноженная на ожидаемую длительность цикла.
     *
     * Ставка приходит ПАРАМЕТРОМ, а не из конфигурации бота, по той же причине, что и
     * комиссия: тариф принадлежит подключению, и копия его в настройках каждого бота
     * означала бы два расходящихся ответа на один вопрос.
     *
     * Длительность цикла, наоборот, свойство самой сетки. Сколько он проживёт на деле,
     * заранее не знает никто, поэтому умолчание в одни сутки намеренно скромное: оно
     * не притворяется прогнозом, а лишь не даёт забыть про издержку целиком.
     */
    private static BigDecimal carryPerCycleRate(GridConfig cfg, BigDecimal carryDailyRate) {
        if (cfg.direction() != GridDirection.SHORT
                || carryDailyRate == null || carryDailyRate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        int days = cfg.expectedCycleDays() == null || cfg.expectedCycleDays() <= 0
                ? 1
                : cfg.expectedCycleDays();
        return carryDailyRate.multiply(BigDecimal.valueOf(days));
    }

    private static String pct(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
