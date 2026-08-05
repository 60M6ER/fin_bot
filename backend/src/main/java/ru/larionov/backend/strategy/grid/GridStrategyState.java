package ru.larionov.backend.strategy.grid;

import java.time.Instant;
import java.math.BigDecimal;

/** Перезаписываемая контрольная точка GRID; денежные операции сюда не входят. */
public record GridStrategyState(
        GridRange activeRange,
        long generation,
        boolean awaitingUpperReplacement,
        Instant lastReplacementAt,
        boolean awaitingDownwardReplacement,
        GridRange pendingRange,
        int downwardReplacements,
        BigDecimal realizedDownwardLoss,
        BigDecimal downwardLossBaseline
) {

    public GridStrategyState(GridRange activeRange, long generation) {
        this(activeRange, generation, false, null,
                false, null, 0, BigDecimal.ZERO, null);
    }

    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                false, null, 0, BigDecimal.ZERO, null);
    }

    public GridStrategyState {
        downwardReplacements = Math.max(0, downwardReplacements);
        if (realizedDownwardLoss == null || realizedDownwardLoss.signum() < 0) {
            realizedDownwardLoss = BigDecimal.ZERO;
        }
    }
}
