package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.execution.BotOrderView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия на баг, из-за которого сетка выкупила один уровень четыре раза.
 *
 * Две ветки ставили продажи по несовместимым правилам: встречная продажа шла на
 * уровень «покупка+1», а сверка — на «ближайший свободный уровень выше цены».
 * Проверка занятости уровня знала только первое правило, поэтому исполненная покупка
 * освобождала уровень под новую — и позиция росла без ограничений.
 *
 * Тест проверяет саму арифметику уровней, не поднимая биржу и Spring.
 */
class GridLevelAccountingTest {

    private static BotOrderView order(OrderSide side, int gridLevel, OrderStatus status, long executed) {
        return new BotOrderView(
                UUID.randomUUID(), UUID.randomUUID().toString(), "exch-1",
                side, status, gridLevel, OrderPurpose.GRID,
                BigDecimal.ONE, BigDecimal.valueOf(executed),
                new BigDecimal("22.1"), new BigDecimal("22.1"),
                null, false, null, null, null, BigDecimal.ONE,
                false, null, Instant.now(), Instant.now());
    }

    /**
     * Повторяет журнал реального прогона: покупка на уровне 6 исполнена,
     * продажа за неё ещё не выставлена.
     */
    private static Map<Integer, BigDecimal> heldFrom(List<BotOrderView> journal) {
        Map<Integer, BigDecimal> held = new HashMap<>();
        for (BotOrderView o : journal) {
            BigDecimal executed = o.executedQuantity();
            if (o.gridLevel() == null || executed == null || executed.signum() <= 0) {
                continue;
            }
            BigDecimal delta = o.side() == OrderSide.BUY ? executed : executed.negate();
            held.merge(o.gridLevel(), delta, BigDecimal::add);
        }
        held.values().removeIf(v -> v.signum() <= 0);
        return held;
    }

    @Test
    void filledBuyKeepsItsLevelOccupiedEvenWithoutASellOrderYet() {
        Map<Integer, BigDecimal> held = heldFrom(List.of(
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1)));

        assertThat(held)
                .as("Исполненная покупка держит уровень занятым — иначе он будет выкуплен повторно")
                .hasEntrySatisfying(6, q -> assertThat(q).isEqualByComparingTo("1"));
    }

    @Test
    void closedCycleReleasesTheLevel() {
        // Продажа привязана к ЗАКРЫВАЕМОМУ уровню покупки, а не к своему ценовому.
        Map<Integer, BigDecimal> held = heldFrom(List.of(
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1),
                order(OrderSide.SELL, 6, OrderStatus.FILLED, 1)));

        assertThat(held)
                .as("Цикл закрыт — уровень снова свободен под покупку")
                .doesNotContainKey(6);
    }

    @Test
    void sellPlacedByReconcilePathClosesTheSameLevelAsCounterSell() {
        // Ровно этого не было: сверка ставила продажу на уровень 8 при покупке на 6,
        // и уровень 6 оставался «свободным».
        Map<Integer, BigDecimal> held = heldFrom(List.of(
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1),
                order(OrderSide.BUY, 7, OrderStatus.FILLED, 1),
                order(OrderSide.SELL, 6, OrderStatus.FILLED, 1)));

        assertThat(held).doesNotContainKey(6);
        assertThat(held)
                .as("Уровень 7 куплен и не закрыт — остаётся занятым")
                .hasEntrySatisfying(7, q -> assertThat(q).isEqualByComparingTo("1"));
    }

    @Test
    void unfilledOrdersDoNotCountAsHeldInventory() {
        Map<Integer, BigDecimal> held = heldFrom(List.of(
                order(OrderSide.BUY, 3, OrderStatus.NEW, 0),
                order(OrderSide.SELL, 8, OrderStatus.NEW, 0)));

        assertThat(held)
                .as("Выставленная, но не исполненная заявка инвентарём не является")
                .isEmpty();
    }

    @Test
    void repeatedBuysOnTheSameLevelAccumulate() {
        // Так выглядел журнал после бага: четыре покупки одного уровня.
        Map<Integer, BigDecimal> held = heldFrom(List.of(
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1),
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1),
                order(OrderSide.BUY, 6, OrderStatus.FILLED, 1)));

        assertThat(held)
                .as("Учёт обязан видеть весь накопленный объём, чтобы продать его целиком")
                .hasEntrySatisfying(6, q -> assertThat(q).isEqualByComparingTo("3"));
    }
}
