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
        /** Размер заявки по уровням покупки: индекс — уровень. */
        List<Long> lotsByLevel,
        String sizingMode,
        BigDecimal workingBudget,
        BigDecimal worstCaseNotional
) {
    public StrategySnapshot {
        ladderPrices = ladderPrices == null ? List.of() : List.copyOf(ladderPrices);
        lotsByLevel = lotsByLevel == null ? List.of() : List.copyOf(lotsByLevel);
    }
}
