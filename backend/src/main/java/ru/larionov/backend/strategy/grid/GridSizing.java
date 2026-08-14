package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Сколько покупать на каждом уровне сетки.
 *
 * Ровно обратная задача к расчёту «худшего случая» в {@link GridValidator}: там из
 * количества выводится требуемый капитал, здесь из выделенного бюджета — количество.
 * Поэтому бюджет обеспечен ПО ПОСТРОЕНИЮ ({@code worstCaseNotional <= workingBudget}),
 * а не проверкой в момент постановки заявки: округление ВНИЗ до торгуемого шага
 * не оставляет возможности выйти за бюджет даже при полном выкупе всех уровней.
 *
 * <p>Количество дробное и выражено в единицах базового актива. Округление до шага
 * биржи ({@code quantityStep}) здесь же, а не в гейтвее: иначе «худший случай»
 * считался бы по одному количеству, а на биржу уходило другое.
 *
 * <p>Уровни покупки — {@code 0..N-1}, где {@code N = ladder.levelCount()}. Верхний
 * уровень {@code N} продажный: встречной продажи для покупки на нём не существует
 * ({@code ladder.priceAt(N + 1) == null}).
 */
public record GridSizing(
        /** Индекс — уровень ПОКУПКИ, значение — количество базового актива. */
        List<BigDecimal> quantityByLevel,
        /** Сколько денег потребуется при выкупе всех уровней покупки. */
        BigDecimal worstCaseNotional,
        /** Неиспользованный остаток бюджета (null в FIXED_QUANTITY). */
        BigDecimal budgetLeftover,
        /** Бюджет, от которого считали (null в FIXED_QUANTITY). */
        BigDecimal workingBudget,
        GridConfig.SizingMode mode
) {

    public GridSizing {
        quantityByLevel = quantityByLevel == null ? List.of() : List.copyOf(quantityByLevel);
    }

    /**
     * Количество на уровне.
     *
     * В {@link GridConfig.SizingMode#FIXED_QUANTITY} размер одинаков на ЛЮБОМ уровне,
     * включая верхний: {@code placeMissingBuys} умеет поставить покупку на уровень N,
     * когда цена ушла выше диапазона, и старое поведение обязано сохраниться.
     * В бюджетных режимах уровень N получает ноль — покупка там не ставится.
     */
    public BigDecimal quantityAt(int level) {
        if (level < 0) {
            return BigDecimal.ZERO;
        }
        if (mode == GridConfig.SizingMode.FIXED_QUANTITY) {
            return quantityByLevel.isEmpty() ? BigDecimal.ZERO : quantityByLevel.get(0);
        }
        return level < quantityByLevel.size() ? quantityByLevel.get(level) : BigDecimal.ZERO;
    }

    public BigDecimal minQuantity() {
        return quantityByLevel.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public BigDecimal maxQuantity() {
        return quantityByLevel.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    /** Одинаков ли размер на всех уровнях покупки. */
    public boolean uniform() {
        return minQuantity().compareTo(maxQuantity()) == 0;
    }

    public static GridSizing fixed(BigDecimal quantityPerOrder, GridLadder ladder, BigDecimal quantityStep) {
        BigDecimal quantity = quantizeDown(quantityPerOrder, quantityStep);
        if (quantity.signum() <= 0) {
            throw new IllegalStateException(
                    ("Размер заявки %s меньше шага количества %s — такую заявку биржа не примет.")
                            .formatted(plain(quantityPerOrder), plain(quantityStep)));
        }

        int levels = Math.max(0, ladder.levelCount());
        List<BigDecimal> quantities = new ArrayList<>(levels);
        BigDecimal worstCase = BigDecimal.ZERO;
        for (int i = 0; i < levels; i++) {
            quantities.add(quantity);
            worstCase = worstCase.add(ladder.priceAt(i).multiply(quantity));
        }
        return new GridSizing(quantities, worstCase, null, null, GridConfig.SizingMode.FIXED_QUANTITY);
    }

    public static GridSizing fromBudget(GridConfig cfg,
                                        GridLadder ladder,
                                        BigDecimal quantityStep,
                                        BigDecimal workingBudget) {

        int levels = ladder.levelCount();
        if (levels <= 0) {
            throw new IllegalStateException("В сетке нет уровней покупки — размер заявки считать не от чего");
        }

        /*
         * Какие уровни ОТКРЫВАЮТ позицию — зависит от направления.
         *
         * У лонга это 0..N−1: на верхнем уровне покупать бессмысленно, продавать
         * с него некуда. У шорта зеркально — 1..N: на нижнем уровне продавать
         * бессмысленно, откупать под ним нечего.
         *
         * Пока сетка была только лонговой, диапазон был зашит как 0..N−1, и для
         * шорта это дало бы сразу две ошибки: нижний уровень получил бы размер,
         * которым его не закрыть, а верхний остался бы пустым, хотя именно он
         * ближе всего к рынку и открывается первым.
         */
        boolean shortMode = cfg.direction() == GridDirection.SHORT;
        int firstOpen = shortMode ? 1 : 0;
        int lastOpen = shortMode ? levels : levels - 1;
        if (workingBudget == null || workingBudget.signum() <= 0) {
            throw new IllegalStateException(
                    ("Рабочий бюджет исчерпан: %s. Реализованный убыток съел выделенные деньги, "
                            + "торговать нечем. Увеличьте бюджет или отключите реинвестирование прибыли.")
                            .formatted(workingBudget == null ? "не задан" : workingBudget.toPlainString()));
        }

        List<BigDecimal> quantities = new ArrayList<>(levels);

        if (cfg.sizingMode() == GridConfig.SizingMode.UNIFORM) {
            BigDecimal denominator = BigDecimal.ZERO;
            for (int i = firstOpen; i <= lastOpen; i++) {
                denominator = denominator.add(ladder.priceAt(i));
            }
            BigDecimal perOrder = quantizeDown(
                    workingBudget.divide(denominator, 18, RoundingMode.DOWN), quantityStep);
            if (perOrder.signum() <= 0) {
                throw new IllegalStateException(
                        ("Бюджета %s не хватает даже на минимальный шаг %s на каждом уровне: "
                                + "полный выкуп %d уровней стоил бы минимум %s. Увеличьте бюджет "
                                + "или уменьшите число уровней.")
                                .formatted(workingBudget.toPlainString(), plain(quantityStep), levels,
                                        denominator.multiply(quantityStep)
                                                .setScale(2, RoundingMode.HALF_UP).toPlainString()));
            }
            for (int i = firstOpen; i <= lastOpen; i++) {
                quantities.add(perOrder);
            }
        } else {
            BigDecimal perLevel = workingBudget.divide(BigDecimal.valueOf(levels), 18, RoundingMode.DOWN);
            for (int i = firstOpen; i <= lastOpen; i++) {
                BigDecimal price = ladder.priceAt(i);
                BigDecimal atLevel = quantizeDown(
                        perLevel.divide(price, 18, RoundingMode.DOWN), quantityStep);
                if (atLevel.signum() <= 0) {
                    // Молча пропустить уровень нельзя: сетка с дырой наверху перестаёт
                    // покупать на спуске до тех пор, пока цена не упадёт достаточно низко,
                    // то есть незаметно меняет форму стратегии.
                    BigDecimal minCost = price.multiply(quantityStep);
                    BigDecimal required = minCost.multiply(BigDecimal.valueOf(levels));
                    throw new IllegalStateException(
                            ("Бюджета %s не хватает на уровень %d (цена %s, шаг количества %s): "
                                    + "при делении поровну на %d уровней на него приходится %s, "
                                    + "а нужно минимум %s. Увеличьте бюджет минимум до %s "
                                    + "или уменьшите число уровней.")
                                    .formatted(workingBudget.toPlainString(), i,
                                            price.toPlainString(), plain(quantityStep), levels,
                                            perLevel.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                            minCost.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                            required.setScale(2, RoundingMode.HALF_UP).toPlainString()));
                }
                quantities.add(atLevel);
            }
        }

        BigDecimal worstCase = BigDecimal.ZERO;
        for (int i = 0; i < quantities.size(); i++) {
            worstCase = worstCase.add(ladder.priceAt(firstOpen + i).multiply(quantities.get(i)));
        }

        /*
         * Список индексируется УРОВНЕМ, поэтому шорту он сдвинут на единицу вперёд:
         * его открывающие уровни 1..N, а нулевой не открывается вовсе.
         *
         * Лонговое представление при этом не меняется ни на элемент — список 0..N−1,
         * как был. Так проверка «размер одинаков на всех уровнях» и остальные
         * потребители продолжают видеть ровно то, что видели всегда.
         */
        List<BigDecimal> byLevel = quantities;
        if (firstOpen > 0) {
            byLevel = new ArrayList<>(quantities.size() + firstOpen);
            for (int i = 0; i < firstOpen; i++) {
                byLevel.add(BigDecimal.ZERO);
            }
            byLevel.addAll(quantities);
        }
        return new GridSizing(byLevel, worstCase, workingBudget.subtract(worstCase),
                workingBudget, cfg.sizingMode());
    }

    /**
     * Округление ВНИЗ до торгуемого шага.
     *
     * Вниз — потому что вверх означало бы заявку чуть больше обеспеченной бюджетом,
     * и гарантия «бюджет обеспечен по построению» перестала бы быть гарантией.
     */
    static BigDecimal quantizeDown(BigDecimal quantity, BigDecimal step) {
        if (quantity == null || quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal effectiveStep = step == null || step.signum() <= 0 ? BigDecimal.ONE : step;
        return quantity.divide(effectiveStep, 0, RoundingMode.DOWN).multiply(effectiveStep);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
