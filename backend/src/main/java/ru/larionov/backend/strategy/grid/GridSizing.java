package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Сколько лотов ставить на каждом уровне сетки.
 *
 * Ровно обратная задача к расчёту «худшего случая» в {@link GridValidator}: там из
 * числа лотов выводится требуемый капитал, здесь из выделенного бюджета — число лотов.
 * Поэтому бюджет обеспечен ПО ПОСТРОЕНИЮ ({@code worstCaseNotional <= workingBudget}),
 * а не проверкой в момент постановки заявки: деление с округлением вниз не оставляет
 * возможности выйти за бюджет даже при полном выкупе всех уровней.
 *
 * <p>Уровни покупки — {@code 0..N-1}, где {@code N = ladder.levelCount()}. Верхний
 * уровень {@code N} продажный: встречной продажи для покупки на нём не существует
 * ({@code ladder.priceAt(N + 1) == null}).
 */
public record GridSizing(
        /** Индекс — уровень ПОКУПКИ. */
        List<Long> lotsByLevel,
        /** Сколько денег потребуется при выкупе всех уровней покупки. */
        BigDecimal worstCaseNotional,
        /** Неиспользованный остаток бюджета (null в FIXED_LOTS). */
        BigDecimal budgetLeftover,
        /** Бюджет, от которого считали (null в FIXED_LOTS). */
        BigDecimal workingBudget,
        GridConfig.SizingMode mode
) {

    public GridSizing {
        lotsByLevel = lotsByLevel == null ? List.of() : List.copyOf(lotsByLevel);
    }

    /**
     * Сколько лотов на уровне.
     *
     * В {@link GridConfig.SizingMode#FIXED_LOTS} размер одинаков на ЛЮБОМ уровне,
     * включая верхний: {@code placeMissingBuys} умеет поставить покупку на уровень N,
     * когда цена ушла выше диапазона, и старое поведение обязано сохраниться.
     * В бюджетных режимах уровень N получает 0 — покупка там не ставится.
     */
    public long lotsAt(int level) {
        if (level < 0) {
            return 0;
        }
        if (mode == GridConfig.SizingMode.FIXED_LOTS) {
            return lotsByLevel.isEmpty() ? 0 : lotsByLevel.get(0);
        }
        return level < lotsByLevel.size() ? lotsByLevel.get(level) : 0;
    }

    public long minLots() {
        return lotsByLevel.stream().mapToLong(Long::longValue).min().orElse(0);
    }

    public long maxLots() {
        return lotsByLevel.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    /** Одинаков ли размер на всех уровнях покупки. */
    public boolean uniform() {
        return minLots() == maxLots();
    }

    public static GridSizing fixed(long lotsPerOrder, GridLadder ladder, int lotSize) {
        int levels = Math.max(0, ladder.levelCount());
        List<Long> lots = new ArrayList<>(levels);
        BigDecimal worstCase = BigDecimal.ZERO;
        for (int i = 0; i < levels; i++) {
            lots.add(lotsPerOrder);
            worstCase = worstCase.add(cost(ladder, i, lotSize).multiply(BigDecimal.valueOf(lotsPerOrder)));
        }
        return new GridSizing(lots, worstCase, null, null, GridConfig.SizingMode.FIXED_LOTS);
    }

    public static GridSizing fromBudget(GridConfig cfg,
                                        GridLadder ladder,
                                        int lotSize,
                                        BigDecimal workingBudget) {

        int levels = ladder.levelCount();
        if (levels <= 0) {
            throw new IllegalStateException("В сетке нет уровней покупки — размер заявки считать не от чего");
        }
        if (workingBudget == null || workingBudget.signum() <= 0) {
            throw new IllegalStateException(
                    ("Рабочий бюджет исчерпан: %s. Реализованный убыток съел выделенные деньги, "
                            + "торговать нечем. Увеличьте бюджет или отключите реинвестирование прибыли.")
                            .formatted(workingBudget == null ? "не задан" : workingBudget.toPlainString()));
        }

        List<Long> lots = new ArrayList<>(levels);

        if (cfg.sizingMode() == GridConfig.SizingMode.UNIFORM) {
            BigDecimal denominator = BigDecimal.ZERO;
            for (int i = 0; i < levels; i++) {
                denominator = denominator.add(cost(ladder, i, lotSize));
            }
            long perOrder = workingBudget.divide(denominator, 0, RoundingMode.DOWN).longValueExact();
            if (perOrder <= 0) {
                throw new IllegalStateException(
                        ("Бюджета %s не хватает даже на один лот на каждом уровне: при лотности %d "
                                + "полный выкуп %d уровней стоит минимум %s. Увеличьте бюджет "
                                + "или уменьшите число уровней.")
                                .formatted(workingBudget.toPlainString(), Math.max(1, lotSize), levels,
                                        denominator.setScale(2, RoundingMode.HALF_UP).toPlainString()));
            }
            for (int i = 0; i < levels; i++) {
                lots.add(perOrder);
            }
        } else {
            BigDecimal perLevel = workingBudget.divide(BigDecimal.valueOf(levels), 9, RoundingMode.DOWN);
            for (int i = 0; i < levels; i++) {
                BigDecimal cost = cost(ladder, i, lotSize);
                long atLevel = perLevel.divide(cost, 0, RoundingMode.DOWN).longValueExact();
                if (atLevel <= 0) {
                    // Молча пропустить уровень нельзя: сетка с дырой наверху перестаёт
                    // покупать на спуске до тех пор, пока цена не упадёт достаточно низко,
                    // то есть незаметно меняет форму стратегии.
                    BigDecimal required = cost.multiply(BigDecimal.valueOf(levels));
                    throw new IllegalStateException(
                            ("Бюджета %s не хватает на уровень %d (цена %s, лотность %d): при делении "
                                    + "поровну на %d уровней на него приходится %s, а нужно минимум %s. "
                                    + "Увеличьте бюджет минимум до %s или уменьшите число уровней.")
                                    .formatted(workingBudget.toPlainString(), i,
                                            ladder.priceAt(i).toPlainString(), Math.max(1, lotSize), levels,
                                            perLevel.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                            cost.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                            required.setScale(2, RoundingMode.HALF_UP).toPlainString()));
                }
                lots.add(atLevel);
            }
        }

        BigDecimal worstCase = BigDecimal.ZERO;
        for (int i = 0; i < levels; i++) {
            worstCase = worstCase.add(cost(ladder, i, lotSize).multiply(BigDecimal.valueOf(lots.get(i))));
        }
        return new GridSizing(lots, worstCase, workingBudget.subtract(worstCase),
                workingBudget, cfg.sizingMode());
    }

    /** Стоимость одного лота на уровне: цена × лотность. */
    private static BigDecimal cost(GridLadder ladder, int level, int lotSize) {
        return ladder.priceAt(level).multiply(BigDecimal.valueOf(lotSize <= 0 ? 1 : lotSize));
    }
}
