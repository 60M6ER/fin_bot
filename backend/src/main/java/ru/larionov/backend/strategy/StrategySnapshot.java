package ru.larionov.backend.strategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Атомарный снимок действующих параметров стратегии для runtime API. */
public record StrategySnapshot(
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        List<BigDecimal> ladderPrices,
        String rangeOrigin,
        Instant rangeSince,
        long generation,
        boolean buyingStopped,
        boolean awaitingReplacement,
        String replacementDirection,
        int downwardReplacements,
        BigDecimal realizedDownwardLoss,
        boolean halted,
        /** Запрошена плановая остановка: покупок нет, ждём распродажи позиции. */
        boolean stopScheduled,
        /** Размер заявки по уровням покупки в единицах базового актива: индекс — уровень. */
        List<BigDecimal> quantityByLevel,
        String sizingMode,
        BigDecimal workingBudget,
        BigDecimal worstCaseNotional
) {
    public StrategySnapshot {
        ladderPrices = ladderPrices == null ? List.of() : List.copyOf(ladderPrices);
        quantityByLevel = quantityByLevel == null ? List.of() : List.copyOf(quantityByLevel);
    }
}
