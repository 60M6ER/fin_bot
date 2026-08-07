package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface MoneyLedgerRepository extends JpaRepository<MoneyLedgerEntity, Long> {

    List<MoneyLedgerEntity> findAllByBotIdAndDryRunOrderBySeqAsc(UUID botId, boolean dryRun);

    List<MoneyLedgerEntity> findTop200ByBotIdAndDryRunOrderBySeqDesc(UUID botId, boolean dryRun);

    boolean existsByOrderIdAndEntryTypeAndExecutedQuantityCum(
            UUID orderId, LedgerEntryType entryType, BigDecimal executedQuantityCum);

    List<MoneyLedgerEntity> findAllByOrderIdAndEntryTypeInOrderBySeqAsc(
            UUID orderId, List<LedgerEntryType> entryTypes);

    @Query("""
            select coalesce(max(l.executedQuantityCum), 0)
            from MoneyLedgerEntity l
            where l.orderId = :orderId and l.entryType in :types
            """)
    BigDecimal maxExecutedQuantityCum(@Param("orderId") UUID orderId, @Param("types") List<LedgerEntryType> types);

    @Query("""
            select coalesce(sum(l.commission), 0)
            from MoneyLedgerEntity l
            where l.orderId = :orderId
              and l.entryType in :types
            """)
    BigDecimal sumCommission(@Param("orderId") UUID orderId, @Param("types") List<LedgerEntryType> types);

    @Query("""
            select coalesce(sum(l.amount), 0)
            from MoneyLedgerEntity l
            where l.botId = :botId and l.dryRun = :dryRun and l.affectsCash = true
            """)
    BigDecimal cashFlow(@Param("botId") UUID botId, @Param("dryRun") boolean dryRun);

    /**
     * Верхняя граница книги на сейчас — ею отрезаются поколения сетки друг от друга.
     * Ноль на пустой книге означает «поколение видит её целиком», что для первого
     * поколения и требуется.
     */
    @Query("""
            select coalesce(max(l.seq), 0)
            from MoneyLedgerEntity l
            where l.botId = :botId and l.dryRun = :dryRun
            """)
    long maxSeq(@Param("botId") UUID botId, @Param("dryRun") boolean dryRun);
}
