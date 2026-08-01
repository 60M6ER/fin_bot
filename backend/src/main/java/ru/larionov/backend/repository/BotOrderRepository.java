package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BotOrderRepository extends JpaRepository<BotOrderEntity, UUID> {

    Optional<BotOrderEntity> findByClientOrderId(String clientOrderId);

    List<BotOrderEntity> findAllByBotIdAndStatusIn(UUID botId, List<OrderStatus> statuses);

    List<BotOrderEntity> findTop200ByBotIdOrderByCreatedAtDesc(UUID botId);

    boolean existsByBotIdAndStatusIn(UUID botId, List<OrderStatus> statuses);

    /** Счётчик для суточного лимита ордеров: считаем попытки постановки, а не исполнения. */
    long countByBotIdAndCreatedAtAfter(UUID botId, Instant after);

    /**
     * Позиция по журналу: куплено минус продано, в лотах.
     * Считается по фактически исполненным объёмам, поэтому переживает рестарт.
     */
    @Query("""
            select coalesce(sum(case when o.side = ru.larionov.backend.exchange.api.enums.OrderSide.BUY
                                     then o.executedLots else -o.executedLots end), 0)
            from BotOrderEntity o
            where o.botId = :botId and o.dryRun = :dryRun
            """)
    long sumPositionLots(@Param("botId") UUID botId, @Param("dryRun") boolean dryRun);
}
