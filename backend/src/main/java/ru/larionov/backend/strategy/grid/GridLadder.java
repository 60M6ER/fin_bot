package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Ценовая лесенка сетки: уровни 0..levels от нижней границы к верхней.
 *
 * Все цены округлены к шагу цены инструмента — биржа не примет заявку с ценой
 * не кратной minPriceIncrement, и это обязано быть учтено при построении,
 * а не в момент выставления.
 */
public final class GridLadder {

    private final List<BigDecimal> prices;
    private final BigDecimal priceIncrement;

    private GridLadder(List<BigDecimal> prices, BigDecimal priceIncrement) {
        this.prices = List.copyOf(prices);
        this.priceIncrement = priceIncrement;
    }

    public static GridLadder build(GridConfig cfg, BigDecimal priceIncrement) {
        BigDecimal increment = (priceIncrement == null || priceIncrement.signum() <= 0)
                ? new BigDecimal("0.000000001")
                : priceIncrement;

        BigDecimal rawStep = cfg.rawStep();
        List<BigDecimal> result = new ArrayList<>(cfg.levels() + 1);

        for (int i = 0; i <= cfg.levels(); i++) {
            BigDecimal raw = cfg.lowerPrice().add(rawStep.multiply(BigDecimal.valueOf(i)));
            result.add(roundToIncrement(raw, increment));
        }
        return new GridLadder(result, increment);
    }

    /** Округление вниз к сетке шага цены: заявка с некратной ценой будет отвергнута. */
    public static BigDecimal roundToIncrement(BigDecimal value, BigDecimal increment) {
        if (increment == null || increment.signum() <= 0) {
            return value;
        }
        return value.divide(increment, 0, RoundingMode.HALF_UP)
                .multiply(increment)
                .stripTrailingZeros();
    }

    public int levelCount() {
        return prices.size() - 1;
    }

    public BigDecimal priceAt(int level) {
        if (level < 0 || level >= prices.size()) {
            return null;
        }
        return prices.get(level);
    }

    public List<BigDecimal> prices() {
        return prices;
    }

    public BigDecimal priceIncrement() {
        return priceIncrement;
    }

    /**
     * Фактический шаг после округления. Может отличаться от расчётного: именно его,
     * а не «идеальный», нужно сравнивать с комиссией.
     */
    public BigDecimal effectiveStep() {
        if (prices.size() < 2) {
            return BigDecimal.ZERO;
        }
        // Берём минимальный шаг: если он не окупает комиссию, то и сетка местами не окупает.
        BigDecimal min = null;
        for (int i = 1; i < prices.size(); i++) {
            BigDecimal step = prices.get(i).subtract(prices.get(i - 1));
            if (min == null || step.compareTo(min) < 0) {
                min = step;
            }
        }
        return min == null ? BigDecimal.ZERO : min;
    }

    /** Наибольший уровень строго ниже цены — туда ставим покупку. */
    public int highestLevelBelow(BigDecimal price) {
        for (int i = levelCount(); i >= 0; i--) {
            if (prices.get(i).compareTo(price) < 0) {
                return i;
            }
        }
        return -1;
    }

    /** Наименьший уровень строго выше цены — туда ставим продажу. */
    public int lowestLevelAbove(BigDecimal price) {
        for (int i = 0; i <= levelCount(); i++) {
            if (prices.get(i).compareTo(price) > 0) {
                return i;
            }
        }
        return -1;
    }
}
