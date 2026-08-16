package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BotOrderRepository extends JpaRepository<BotOrderEntity, UUID> {

    Optional<BotOrderEntity> findByClientOrderId(String clientOrderId);

    List<BotOrderEntity> findAllByBotIdAndStatusIn(UUID botId, List<OrderStatus> statuses);

    List<BotOrderEntity> findAllByBotIdAndDryRunAndFeeActualFalseAndExecutedQuantityGreaterThan(
            UUID botId, boolean dryRun, BigDecimal executedQuantity);

    List<BotOrderEntity> findTop200ByBotIdOrderByCreatedAtDesc(UUID botId);

    List<BotOrderEntity> findAllByBotIdAndPurposeAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
            UUID botId, OrderPurpose purpose, Instant since);

    boolean existsByBotIdAndStatusIn(UUID botId, List<OrderStatus> statuses);

    /** Счётчик для суточного лимита ордеров: считаем попытки постановки, а не исполнения. */
    long countByBotIdAndCreatedAtAfter(UUID botId, Instant after);

    /**
     * Ордера, из которых складывается поуровневый учёт сетки: только исполненные,
     * только со своим уровнем, только текущего поколения.
     *
     * Отдельный метод вместо {@link #findTop200ByBotIdOrderByCreatedAtDesc} — и это
     * не косметика. Поуровневый остаток считается как «куплено минус продано», а
     * продажа ВСЕГДА новее своей покупки: на границе любого окна покупка выпадает
     * раньше закрывшей её продажи, и баланс уровня систематически уезжает в минус.
     * 10.08.2026 на боте DOGE это стоило застрявшей позиции: по всей истории на
     * уровне 8 лежало +20, по последним 200 строкам — ровно 0. Уровень исчез из
     * учёта, встречная продажа не выставилась ни разу, уровень трижды перекупили,
     * а перестановка диапазона вверх встала навсегда, ожидая закрытия позиции,
     * которую сама же перестала видеть.
     *
     * Отсечка по времени начала диапазона здесь же, а не в стратегии: номер уровня
     * осмыслен только внутри своего поколения, и лишние строки незачем везти в память.
     */
    @Query("""
            select o from BotOrderEntity o
            where o.botId = :botId and o.dryRun = :dryRun
              and o.gridLevel is not null
              and o.executedQuantity > 0
              and o.createdAt >= :since
            order by o.createdAt asc
            """)
    List<BotOrderEntity> findLevelOrders(@Param("botId") UUID botId,
                                         @Param("dryRun") boolean dryRun,
                                         @Param("since") Instant since);

    /**
     * Позиция по журналу: куплено минус продано, в единицах базового актива.
     * Считается по фактически исполненным объёмам, поэтому переживает рестарт.
     */
    @Query("""
            select coalesce(sum(case when o.side = ru.larionov.backend.exchange.api.enums.OrderSide.BUY
                                     then o.executedQuantity else -o.executedQuantity end), 0)
            from BotOrderEntity o
            where o.botId = :botId and o.dryRun = :dryRun
            """)
    BigDecimal sumPositionQuantity(@Param("botId") UUID botId, @Param("dryRun") boolean dryRun);
}
