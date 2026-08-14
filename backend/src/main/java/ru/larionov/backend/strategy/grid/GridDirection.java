package ru.larionov.backend.strategy.grid;

import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;

import java.math.BigDecimal;
import java.util.List;

/**
 * Направление сетки: лонг покупает ниже и продаёт выше, шорт — зеркально.
 *
 * Здесь собрано ВСЁ, чем направления отличаются, и это единственная причина
 * существования перечисления. Сетка в остальном симметрична: подтверждение пробоя,
 * сверка, комиссии, бюджет, паузы после отказов биржи одинаковы для обеих сторон,
 * и размазывать по ним {@code if (short)} значило бы завести полтора десятка мест,
 * каждое из которых можно забыть поправить поодиночке.
 *
 * Методы абстрактные намеренно. Константу нельзя объявить, не реализовав их все, —
 * значит новый вопрос о направлении, добавленный сюда, невозможно оставить без
 * ответа для одной из сторон: это не соглашение, а отказ компилятора.
 */
public enum GridDirection {

    /** Покупаем на уровне i, продаём на i+1. Позиция положительна. */
    LONG {
        @Override
        public OrderSide openSide() {
            return OrderSide.BUY;
        }

        @Override
        public OrderSide closeSide() {
            return OrderSide.SELL;
        }

        @Override
        public int closeLevelOf(int openLevel) {
            return openLevel + 1;
        }

        @Override
        public int firstOpenLevel(GridLadder ladder, BigDecimal price) {
            return ladder.highestLevelBelow(price);
        }

        @Override
        public int openScanStep() {
            return -1;
        }

        @Override
        public int lastOpenLevel(GridLadder ladder) {
            return 0;
        }

        @Override
        public BigDecimal adverseBound(GridRange range) {
            return range.lower();
        }

        @Override
        public BigDecimal favourableBound(GridRange range) {
            return range.upper();
        }

        @Override
        public BigDecimal adverseThreshold(BigDecimal bound, BigDecimal margin) {
            return bound.subtract(margin);
        }

        @Override
        public BigDecimal favourableThreshold(BigDecimal bound, BigDecimal margin) {
            return bound.add(margin);
        }

        @Override
        public boolean beyondAdverse(BigDecimal price, BigDecimal bound) {
            return price.compareTo(bound) < 0;
        }

        @Override
        public boolean beyondFavourable(BigDecimal price, BigDecimal bound) {
            return price.compareTo(bound) > 0;
        }

        @Override
        public int sign() {
            return 1;
        }

        @Override
        public BigDecimal unwindPrice(OrderBook book) {
            return bestOf(book == null ? null : book.bids());
        }
    },

    /**
     * Продаём на уровне i, откупаем на i−1. Позиция отрицательна.
     *
     * Возможен только на маржинальном счёте: продаём то, чего нет. Разрешение
     * проверяется вне этого перечисления — оно про геометрию, а не про риск.
     */
    SHORT {
        @Override
        public OrderSide openSide() {
            return OrderSide.SELL;
        }

        @Override
        public OrderSide closeSide() {
            return OrderSide.BUY;
        }

        @Override
        public int closeLevelOf(int openLevel) {
            return openLevel - 1;
        }

        @Override
        public int firstOpenLevel(GridLadder ladder, BigDecimal price) {
            return ladder.lowestLevelAbove(price);
        }

        @Override
        public int openScanStep() {
            return 1;
        }

        @Override
        public int lastOpenLevel(GridLadder ladder) {
            return ladder.levelCount();
        }

        @Override
        public BigDecimal adverseBound(GridRange range) {
            return range.upper();
        }

        @Override
        public BigDecimal favourableBound(GridRange range) {
            return range.lower();
        }

        @Override
        public BigDecimal adverseThreshold(BigDecimal bound, BigDecimal margin) {
            return bound.add(margin);
        }

        @Override
        public BigDecimal favourableThreshold(BigDecimal bound, BigDecimal margin) {
            return bound.subtract(margin);
        }

        @Override
        public boolean beyondAdverse(BigDecimal price, BigDecimal bound) {
            return price.compareTo(bound) > 0;
        }

        @Override
        public boolean beyondFavourable(BigDecimal price, BigDecimal bound) {
            return price.compareTo(bound) < 0;
        }

        @Override
        public int sign() {
            return -1;
        }

        @Override
        public BigDecimal unwindPrice(OrderBook book) {
            return bestOf(book == null ? null : book.asks());
        }
    };

    /** Сторона, которая НАБИРАЕТ позицию. */
    public abstract OrderSide openSide();

    /** Сторона, которая закрывает набранное и фиксирует результат цикла. */
    public abstract OrderSide closeSide();

    /** Уровень встречной заявки: лонг закрывает уровнем выше, шорт — уровнем ниже. */
    public abstract int closeLevelOf(int openLevel);

    /**
     * Ближайший к рынку уровень, на который имеет смысл ставить открывающую заявку.
     * Лонгу — ближайший снизу, шорту — ближайший сверху. {@code -1}, если такого нет.
     */
    public abstract int firstOpenLevel(GridLadder ladder, BigDecimal price);

    /** Куда идёт обход уровней от {@link #firstOpenLevel}: вниз у лонга, вверх у шорта. */
    public abstract int openScanStep();

    /** Последний уровень обхода включительно. */
    public abstract int lastOpenLevel(GridLadder ladder);

    /** Граница, пробой которой ПРОТИВ нас: лонгу нижняя, шорту верхняя. */
    public abstract BigDecimal adverseBound(GridRange range);

    /** Граница, пробой которой в нашу пользу: позиция распродана, диапазон пора двигать. */
    public abstract BigDecimal favourableBound(GridRange range);

    /** Порог подтверждения неблагоприятного пробоя: граница плюс запас в сторону движения. */
    public abstract BigDecimal adverseThreshold(BigDecimal bound, BigDecimal margin);

    public abstract BigDecimal favourableThreshold(BigDecimal bound, BigDecimal margin);

    /** Цена вышла за неблагоприятную границу. */
    public abstract boolean beyondAdverse(BigDecimal price, BigDecimal bound);

    /** Цена вышла за благоприятную границу. */
    public abstract boolean beyondFavourable(BigDecimal price, BigDecimal bound);

    /** Знак позиции этого направления: +1 лонг, −1 шорт. */
    public abstract int sign();

    /**
     * Цена, по которой позицию можно закрыть прямо сейчас: лучший бид для лонга,
     * лучший аск для шорта. Стакан спрашивается на глубину 1.
     *
     * @return null, если стакан пуст — вызывающий решает, ошибка это или ожидание
     */
    public abstract BigDecimal unwindPrice(OrderBook book);

    public GridDirection opposite() {
        return this == LONG ? SHORT : LONG;
    }

    /** Роль заявки этой стороны в сетке текущего направления. */
    public GridRole roleOf(OrderSide side) {
        return side == openSide() ? GridRole.OPEN : GridRole.CLOSE;
    }

    /** Позиция, которую ожидаем увидеть при указанной экспозиции по модулю. */
    public BigDecimal signedPosition(BigDecimal exposure) {
        if (exposure == null) {
            return null;
        }
        return sign() > 0 ? exposure : exposure.negate();
    }

    private static BigDecimal bestOf(List<OrderBookLevel> side) {
        if (side == null || side.isEmpty() || side.get(0) == null || side.get(0).price() == null) {
            return null;
        }
        BigDecimal value = side.get(0).price().value();
        return value == null || value.signum() <= 0 ? null : value;
    }
}
