package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.InstrumentEntity;
import ru.larionov.backend.enums.ExchangeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstrumentRepository extends JpaRepository<InstrumentEntity, UUID> {

    Optional<InstrumentEntity> findByExchangeAndInstrumentUid(ExchangeType exchange, String instrumentUid);

    /** Поиск по тикеру. Нужен курсу валют: пара USD/RUB известна именно тикером. */
    Optional<InstrumentEntity> findFirstByExchangeAndTickerAndActiveTrue(
            ExchangeType exchange, String ticker);

    List<InstrumentEntity> findAllByInstrumentUid(String instrumentUid);

    long countByExchangeAndActiveTrue(ExchangeType exchange);

    /** Когда справочник этой биржи обновляли в последний раз. Пусто = не обновляли ни разу. */
    @Query("select max(i.syncedAt) from InstrumentEntity i where i.exchange = :exchange")
    Optional<Instant> findLastSyncedAt(@Param("exchange") ExchangeType exchange);

    /**
     * Поиск для автокомплита.
     *
     * Ранжирование (внешний CASE): точный тикер → префикс тикера → префикс названия →
     * подстрока названия → подстрока тикера. Затем приоритет вида — набравший «SB» хочет
     * SBER, а не колл-опцион на него. Затем короткий тикер вперёд (SBER раньше SBERP)
     * и алфавит, чтобы порядок был стабильным между запросами.
     *
     * Предикаты написаны как lower(i.ticker) LIKE :prefix — ровно выражение из индекса
     * idx_instrument_ticker_prefix. Любое отклонение (ILIKE, upper) уведёт запрос в seq scan.
     */
    @Query(nativeQuery = true, value = """
            SELECT i.* FROM instrument i
             WHERE i.exchange = :exchange
               AND i.is_active
               AND (CAST(:kind AS varchar) IS NULL OR i.kind = CAST(:kind AS varchar))
               AND (CAST(:segment AS varchar) IS NULL OR i.segment = CAST(:segment AS varchar))
               AND (NOT :onlyTradable OR i.api_trade_available)
               AND ( :q = ''
                     OR lower(i.ticker) LIKE :prefix
                     OR lower(i.name)   LIKE :contains
                     OR lower(i.ticker) LIKE :contains )
             ORDER BY
               CASE
                 WHEN :q = ''                        THEN 5
                 WHEN lower(i.ticker) = :q           THEN 0
                 WHEN lower(i.ticker) LIKE :prefix   THEN 1
                 WHEN lower(i.name)   LIKE :prefix   THEN 2
                 WHEN lower(i.name)   LIKE :contains THEN 3
                 ELSE 4
               END,
               CASE i.kind
                 WHEN 'SHARE'    THEN 0
                 WHEN 'ETF'      THEN 1
                 WHEN 'CURRENCY' THEN 2
                 WHEN 'FUTURE'   THEN 3
                 WHEN 'BOND'     THEN 4
                 ELSE 5
               END,
               length(i.ticker),
               i.ticker
             LIMIT :limit
            """)
    List<InstrumentEntity> search(@Param("exchange") String exchange,
                                  @Param("q") String q,
                                  @Param("prefix") String prefix,
                                  @Param("contains") String contains,
                                  @Param("kind") String kind,
                                  @Param("segment") String segment,
                                  @Param("onlyTradable") boolean onlyTradable,
                                  @Param("limit") int limit);
}
