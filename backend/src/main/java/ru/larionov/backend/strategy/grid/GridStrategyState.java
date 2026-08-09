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
        BigDecimal downwardLossBaseline,
        /**
         * Идущая ликвидация запрошена оператором, а не пробоем.
         *
         * Хранится вместе с остальным состоянием, потому что переживать рестарт обязано:
         * иначе поднявшийся посреди ручной ликвидации бот снова упёрся бы в потолок
         * убытка — с наполовину проданной позицией и без права её дораспродать.
         */
        boolean forcedReplacement,

        /**
         * Оператор запросил плановую остановку: покупок больше нет, ждём распродажи.
         *
         * Переживает рестарт обязательно. Иначе поднятый супервизором бот забыл бы
         * о решении владельца и снова начал покупать — а тот в это время ждёт, когда
         * можно будет безопасно его удалить.
         */
        boolean stopScheduled
) {

    public GridStrategyState(GridRange activeRange, long generation) {
        this(activeRange, generation, false, null,
                false, null, 0, BigDecimal.ZERO, null, false, false);
    }

    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                false, null, 0, BigDecimal.ZERO, null, false, false);
    }

    /** Без признака ручной перестановки: состояние, записанное до её появления. */
    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt,
                             boolean awaitingDownwardReplacement, GridRange pendingRange,
                             int downwardReplacements, BigDecimal realizedDownwardLoss,
                             BigDecimal downwardLossBaseline) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, false, false);
    }

    /** Без признака плановой остановки: состояние, записанное до её появления. */
    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt,
                             boolean awaitingDownwardReplacement, GridRange pendingRange,
                             int downwardReplacements, BigDecimal realizedDownwardLoss,
                             BigDecimal downwardLossBaseline, boolean forcedReplacement) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, forcedReplacement, false);
    }

    public GridStrategyState {
        downwardReplacements = Math.max(0, downwardReplacements);
        if (realizedDownwardLoss == null || realizedDownwardLoss.signum() < 0) {
            realizedDownwardLoss = BigDecimal.ZERO;
        }
    }
}
