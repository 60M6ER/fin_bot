package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Диапазон, по которому стратегия торгует сейчас, независимо от способа его выбора. */
public record GridRange(
        BigDecimal lower,
        BigDecimal upper,
        int levels,
        Origin origin,
        Instant since
) {

    public enum Origin {
        MANUAL,
        ATR_INITIAL,
        ATR_REPLACED_UP,
        ATR_REPLACED_DOWN
    }

    public GridRange {
        if (lower == null || lower.signum() <= 0) {
            throw new IllegalArgumentException("Нижняя граница сетки должна быть больше нуля");
        }
        if (upper == null || upper.compareTo(lower) <= 0) {
            throw new IllegalArgumentException("Верхняя граница сетки должна быть выше нижней");
        }
        if (levels <= 0) {
            throw new IllegalArgumentException("Число уровней сетки должно быть больше нуля");
        }
        if (origin == null) {
            origin = Origin.MANUAL;
        }
        if (since == null) {
            since = Instant.now();
        }
    }

    public static GridRange manual(GridConfig cfg, Instant since) {
        return new GridRange(cfg.lowerPrice(), cfg.upperPrice(), cfg.levels(), Origin.MANUAL, since);
    }

    /** Шаг сетки до округления к шагу цены инструмента. */
    public BigDecimal rawStep() {
        return upper.subtract(lower)
                .divide(BigDecimal.valueOf(levels), 9, RoundingMode.HALF_UP);
    }
}
