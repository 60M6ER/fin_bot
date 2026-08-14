package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Разворот сетки на неблагоприятном пробое.
 *
 * Замысел всей маржинальной работы: пробили вниз — дальше торгуем падение шортом,
 * пробили вверх из шорта — возвращаемся в лонг. Без разворота новое поколение встаёт
 * к тренду спиной и покупает в падение ровно так же, как покупало до пробоя, —
 * перестановка диапазона лечила бы симптом, а не причину.
 *
 * Здесь проверяется геометрия этого решения: какая граница считается неблагоприятной
 * для каждого направления и куда сетка разворачивается. Сам жизненный цикл переворота
 * позиции проверяется в {@code GridStrategyHedgeEpisodeTest}.
 */
class GridDirectionFlipTest {

    private final GridRange range = new GridRange(
            new BigDecimal("100"), new BigDecimal("110"), 10,
            GridRange.Origin.ATR_INITIAL, Instant.EPOCH);

    /** Лонгу вредит падение под нижнюю границу. */
    @Test
    @DisplayName("для лонга неблагоприятна нижняя граница")
    void longFearsTheLowerBound() {
        assertThat(GridDirection.LONG.adverseBound(range)).isEqualByComparingTo("100");
        assertThat(GridDirection.LONG.favourableBound(range)).isEqualByComparingTo("110");

        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("99"), new BigDecimal("100")))
                .as("цена под нижней границей — это пробой против лонга").isTrue();
        assertThat(GridDirection.LONG.beyondAdverse(new BigDecimal("101"), new BigDecimal("100")))
                .isFalse();
    }

    /**
     * Шорту вредит РОСТ над верхней границей — зеркально.
     *
     * Пока это было зашито как «нижняя граница», шортовая сетка в беде запускала бы
     * машинерию, написанную для противоположного случая: рост означал бы для неё
     * успех, а падение — повод закрываться.
     */
    @Test
    @DisplayName("для шорта неблагоприятна верхняя граница")
    void shortFearsTheUpperBound() {
        assertThat(GridDirection.SHORT.adverseBound(range)).isEqualByComparingTo("110");
        assertThat(GridDirection.SHORT.favourableBound(range)).isEqualByComparingTo("100");

        assertThat(GridDirection.SHORT.beyondAdverse(new BigDecimal("111"), new BigDecimal("110")))
                .as("цена над верхней границей — это пробой против шорта").isTrue();
        assertThat(GridDirection.SHORT.beyondAdverse(new BigDecimal("109"), new BigDecimal("110")))
                .isFalse();
    }

    /** Порог подтверждения отсчитывается в сторону движения, а не всегда вниз. */
    @Test
    @DisplayName("порог подтверждения зеркален направлению")
    void thresholdIsMirrored() {
        BigDecimal margin = new BigDecimal("1");

        assertThat(GridDirection.LONG.adverseThreshold(new BigDecimal("100"), margin))
                .as("лонг подтверждает пробой ниже границы").isEqualByComparingTo("99");
        assertThat(GridDirection.SHORT.adverseThreshold(new BigDecimal("110"), margin))
                .as("шорт — выше").isEqualByComparingTo("111");

        assertThat(GridDirection.LONG.favourableThreshold(new BigDecimal("110"), margin))
                .isEqualByComparingTo("111");
        assertThat(GridDirection.SHORT.favourableThreshold(new BigDecimal("100"), margin))
                .isEqualByComparingTo("99");
    }

    /** Разворот замкнут: два подряд возвращают исходное направление. */
    @Test
    @DisplayName("разворот зеркален и обратим")
    void flipIsAnInvolution() {
        assertThat(GridDirection.LONG.opposite()).isEqualTo(GridDirection.SHORT);
        assertThat(GridDirection.SHORT.opposite()).isEqualTo(GridDirection.LONG);
        assertThat(GridDirection.LONG.opposite().opposite()).isEqualTo(GridDirection.LONG);
    }

    /**
     * Направление — часть СОСТОЯНИЯ, а не только конфигурации.
     *
     * После разворота бот обязан подняться тем направлением, которым торговал, а не
     * тем, что записано в настройках. Иначе перезапуск поднял бы лонговую сетку
     * поверх открытой короткой позиции и начал бы «закрывать» её покупками,
     * то есть удваивать.
     */
    @Test
    @DisplayName("направление переживает рестарт")
    void directionSurvivesRestart() {
        GridStrategyState state = new GridStrategyState(
                range, 3, false, null, false, null, 0, BigDecimal.ZERO, null, false, false,
                null, 0, GridDirection.SHORT);

        assertThat(state.direction()).isEqualTo(GridDirection.SHORT);

        // Состояние, записанное до появления направления, читается как лонговое:
        // шорта тогда не существовало, и другого варианта у той истории нет.
        GridStrategyState legacy = new GridStrategyState(range, 1);
        assertThat(legacy.direction()).isNull();
    }
}
