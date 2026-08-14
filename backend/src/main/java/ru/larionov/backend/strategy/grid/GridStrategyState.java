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
        boolean stopScheduled,

        /**
         * Идущий эпизод восстановительного плеча, если он есть.
         *
         * Обёртка, а не набор полей: у эпизода либо есть все числа сразу, либо нет
         * ни одного. Разложенный по отдельным полям, он допускал бы состояние
         * «цена входа есть, цели нет» — то есть открытую позицию без плана выхода.
         */
        HedgeEpisode hedgeEpisode,

        /**
         * Сколько эпизодов уже потрачено в текущем поколении.
         *
         * Считается именно по поколению: лимит существует, чтобы переворот не стал
         * рекурсивным. Каждый следующий эпизод умножает экспозицию, и два подряд
         * дают девятикратную от исходной — так проигрывают счёт за конечное число шагов.
         */
        int hedgeEpisodesUsed
) {

    public GridStrategyState(GridRange activeRange, long generation) {
        this(activeRange, generation, false, null,
                false, null, 0, BigDecimal.ZERO, null, false, false, null, 0);
    }

    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                false, null, 0, BigDecimal.ZERO, null, false, false, null, 0);
    }

    /** Без признака ручной перестановки: состояние, записанное до её появления. */
    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt,
                             boolean awaitingDownwardReplacement, GridRange pendingRange,
                             int downwardReplacements, BigDecimal realizedDownwardLoss,
                             BigDecimal downwardLossBaseline) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, false, false, null, 0);
    }

    /** Без признака плановой остановки: состояние, записанное до её появления. */
    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt,
                             boolean awaitingDownwardReplacement, GridRange pendingRange,
                             int downwardReplacements, BigDecimal realizedDownwardLoss,
                             BigDecimal downwardLossBaseline, boolean forcedReplacement) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, forcedReplacement, false, null, 0);
    }

    /** Без эпизода плеча: состояние, записанное до его появления. */
    public GridStrategyState(GridRange activeRange, long generation,
                             boolean awaitingUpperReplacement, Instant lastReplacementAt,
                             boolean awaitingDownwardReplacement, GridRange pendingRange,
                             int downwardReplacements, BigDecimal realizedDownwardLoss,
                             BigDecimal downwardLossBaseline, boolean forcedReplacement,
                             boolean stopScheduled) {
        this(activeRange, generation, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, forcedReplacement, stopScheduled,
                null, 0);
    }

    public GridStrategyState {
        downwardReplacements = Math.max(0, downwardReplacements);
        hedgeEpisodesUsed = Math.max(0, hedgeEpisodesUsed);
        if (realizedDownwardLoss == null || realizedDownwardLoss.signum() < 0) {
            realizedDownwardLoss = BigDecimal.ZERO;
        }
    }
}
