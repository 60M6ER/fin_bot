package ru.larionov.backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Инцидент 10.08.2026 на боте DOGE, воспроизведённый в базе.
 *
 * Поуровневый остаток — это «куплено минус продано», а встречная продажа ВСЕГДА
 * новее закрываемой ею покупки. Поэтому окно фиксированного размера теряет покупку
 * раньше продажи, и остаток уровня уходит в ноль или в минус: по всей истории на
 * уровне лежит +20, по последним 200 строкам — ровно 0.
 *
 * Последствия были не бумажные. Уровень исчезал из учёта, встречная продажа на него
 * не выставлялась ни разу, уровень перекупали заново, а перестановка диапазона вверх
 * вставала навсегда, ожидая закрытия позиции, которую сама же перестала видеть.
 */
@SpringBootTest
@Transactional
class BotOrderLevelLedgerTest {

    private static final BigDecimal LOT = new BigDecimal("20");
    private static final int NOISE_ROWS = 200;

    private final UUID botId = UUID.randomUUID();
    private final Instant generationStart = Instant.parse("2026-08-08T14:34:06Z");

    @Autowired
    private BotOrderRepository repo;

    @Test
    void levelLedgerSeesTheWholeGenerationWhereTheWindowLosesIt() {
        // Покупка, которую вытеснит окно...
        save(OrderSide.BUY, 8, LOT, at(1));
        // ...двести строк шума: закрытые циклы соседнего уровня...
        for (int i = 0; i < NOISE_ROWS / 2; i++) {
            save(OrderSide.BUY, 1, LOT, at(2 + i * 2L));
            save(OrderSide.SELL, 1, LOT, at(3 + i * 2L));
        }
        // ...её продажа, которая в окне осталась, и новая непроданная покупка.
        save(OrderSide.SELL, 8, LOT, at(1_000));
        save(OrderSide.BUY, 8, LOT, at(1_001));

        assertThat(heldAtLevel8(repo.findLevelOrders(botId, false, Instant.EPOCH)))
                .as("по всей истории поколения на уровне 8 лежит непроданная покупка")
                .isEqualByComparingTo("20");

        assertThat(heldAtLevel8(repo.findTop200ByBotIdOrderByCreatedAtDesc(botId)))
                .as("окно из 200 строк теряет покупку раньше её продажи — уровень выглядит пустым")
                .isEqualByComparingTo("0");
    }

    /** Номер уровня осмыслен только внутри своего поколения — чужие строки не наши. */
    @Test
    void ordersOlderThanTheGenerationAreNotReturned() {
        save(OrderSide.BUY, 8, LOT, generationStart.minusSeconds(1));
        save(OrderSide.BUY, 8, LOT, generationStart);

        assertThat(repo.findLevelOrders(botId, false, generationStart)).hasSize(1);
    }

    /** Неисполненные заявки и ордера без уровня к поуровневому учёту отношения не имеют. */
    @Test
    void unexecutedAndLevellessOrdersAreNotReturned() {
        save(OrderSide.BUY, 8, BigDecimal.ZERO, at(1));
        save(OrderSide.SELL, null, LOT, at(2));
        save(OrderSide.BUY, 8, LOT, at(3));

        assertThat(repo.findLevelOrders(botId, false, Instant.EPOCH))
                .singleElement()
                .satisfies(o -> assertThat(o.getGridLevel()).isEqualTo(8));
    }

    private BigDecimal heldAtLevel8(java.util.List<BotOrderEntity> orders) {
        return orders.stream()
                .filter(o -> Integer.valueOf(8).equals(o.getGridLevel()))
                .map(o -> o.getSide() == OrderSide.BUY
                        ? o.getExecutedQuantity()
                        : o.getExecutedQuantity().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Instant at(long seconds) {
        return generationStart.plusSeconds(seconds);
    }

    private void save(OrderSide side, Integer level, BigDecimal executed, Instant createdAt) {
        repo.save(BotOrderEntity.builder()
                .botId(botId)
                .connectionId(UUID.randomUUID())
                .accountId("acc-1")
                .instrumentUid("uid-1")
                .clientOrderId(UUID.randomUUID().toString())
                .side(side)
                .status(executed.signum() > 0 ? OrderStatus.FILLED : OrderStatus.CANCELLED)
                .gridLevel(level)
                .requestedQuantity(LOT)
                .executedQuantity(executed)
                .exchangeLotSize(BigDecimal.ONE)
                .dryRun(false)
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());
    }
}
