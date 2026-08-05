package ru.larionov.backend.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentSnapshot;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Массовая запись справочника.
 *
 * Намеренно в обход JPA: десятки тысяч строк через Hibernate — это столько же SELECT-ов
 * на dirty-check плюс раздувающийся first-level cache. Здесь один батчевый
 * INSERT ... ON CONFLICT и никакого контекста персистентности.
 */
@Repository
public class InstrumentUpsertDao {

    /**
     * Чанк намеренно небольшой: одна гигантская транзакция держала бы блокировки весь дамп,
     * а падение в середине теряло бы всё сделанное. При почанковых транзакциях частичная
     * синхронизация безвредна — строки просто настолько свежи, насколько успел последний чанк.
     */
    public static final int CHUNK_SIZE = 1000;

    private static final String UPSERT = """
            INSERT INTO instrument (
                id, exchange, instrument_uid, figi, isin, kind, segment, ticker, name,
                class_code, venue, venue_raw, currency, lot, min_price_increment,
                buy_available, sell_available, api_trade_available, short_enabled,
                weekend_trading, trading_status, basic_asset, expires_at, strike_price,
                is_active, synced_at, created_at, updated_at)
            VALUES (
                :id, :exchange, :uid, :figi, :isin, :kind, :segment, :ticker, :name,
                :classCode, :venue, :venueRaw, :currency, :lot, :minPriceIncrement,
                :buyAvailable, :sellAvailable, :apiTradeAvailable, :shortEnabled,
                :weekendTrading, :tradingStatus, :basicAsset, :expiresAt, :strikePrice,
                true, :syncedAt, :now, :now)
            ON CONFLICT (exchange, instrument_uid) DO UPDATE SET
                figi = EXCLUDED.figi,
                isin = EXCLUDED.isin,
                kind = EXCLUDED.kind,
                segment = EXCLUDED.segment,
                ticker = EXCLUDED.ticker,
                name = EXCLUDED.name,
                class_code = EXCLUDED.class_code,
                venue = EXCLUDED.venue,
                venue_raw = EXCLUDED.venue_raw,
                currency = EXCLUDED.currency,
                lot = EXCLUDED.lot,
                min_price_increment = EXCLUDED.min_price_increment,
                buy_available = EXCLUDED.buy_available,
                sell_available = EXCLUDED.sell_available,
                api_trade_available = EXCLUDED.api_trade_available,
                short_enabled = EXCLUDED.short_enabled,
                weekend_trading = EXCLUDED.weekend_trading,
                trading_status = EXCLUDED.trading_status,
                basic_asset = EXCLUDED.basic_asset,
                expires_at = EXCLUDED.expires_at,
                strike_price = EXCLUDED.strike_price,
                is_active = true,
                synced_at = EXCLUDED.synced_at,
                updated_at = EXCLUDED.updated_at
            """;

    /**
     * Инструмент пропал из выгрузки — помечаем неактивным, но не удаляем: на строку
     * ссылаются конфиги ботов и журнал ордеров, и подпись для неё всё равно нужна.
     *
     * Скоуп по kind обязателен. Каждый тип выгружается независимо; если упал дамп опционов,
     * а акции прошли, свип без этого условия пометил бы делистнутой всю вселенную опционов.
     */
    private static final String DEACTIVATE_STALE = """
            UPDATE instrument
               SET is_active = false, updated_at = now()
             WHERE exchange = :exchange
               AND kind IN (:kinds)
               AND synced_at < :runStartedAt
               AND is_active
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public InstrumentUpsertDao(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        // TransactionTemplate Spring Boot сам не поднимает, а @Transactional тут не годится:
        // границы транзакции проходят по чанкам внутри одного вызова, а не по методу.
        this.tx = new TransactionTemplate(txManager);
    }

    /** @return сколько строк ушло в БД */
    public int upsertChunked(ExchangeType exchange, List<InstrumentSnapshot> snapshots, Instant syncedAt) {
        int total = 0;
        for (int from = 0; from < snapshots.size(); from += CHUNK_SIZE) {
            List<InstrumentSnapshot> chunk = snapshots.subList(
                    from, Math.min(from + CHUNK_SIZE, snapshots.size()));
            total += upsertChunk(exchange, chunk, syncedAt);
        }
        return total;
    }

    private int upsertChunk(ExchangeType exchange, List<InstrumentSnapshot> chunk, Instant syncedAt) {
        SqlParameterSource[] params = chunk.stream()
                .map(s -> toParams(exchange, s, syncedAt))
                .toArray(SqlParameterSource[]::new);

        Integer written = tx.execute(status -> {
            int[] counts = jdbc.batchUpdate(UPSERT, params);
            int sum = 0;
            for (int c : counts) {
                // Драйвер может вернуть SUCCESS_NO_INFO (-2) — тогда считаем строку записанной.
                sum += (c < 0 ? 1 : c);
            }
            return sum;
        });
        return written == null ? 0 : written;
    }

    public int deactivateStale(ExchangeType exchange, Collection<InstrumentKind> syncedKinds, Instant runStartedAt) {
        if (syncedKinds.isEmpty()) {
            return 0;
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("exchange", exchange.name())
                .addValue("kinds", syncedKinds.stream().map(Enum::name).toList())
                .addValue("runStartedAt", Timestamp.from(runStartedAt));

        Integer updated = tx.execute(status -> jdbc.update(DEACTIVATE_STALE, p));
        return updated == null ? 0 : updated;
    }

    private static MapSqlParameterSource toParams(ExchangeType exchange, InstrumentSnapshot s, Instant syncedAt) {
        InstrumentBrief b = s.brief();
        Timestamp now = Timestamp.from(Instant.now());

        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("exchange", exchange.name())
                .addValue("uid", b.id().uid())
                .addValue("figi", b.id().figi())
                .addValue("isin", s.isin())
                .addValue("kind", b.kind().name())
                .addValue("segment", b.segment().name())
                .addValue("ticker", nvl(b.ticker(), b.id().uid()))
                .addValue("name", nvl(b.name(), b.id().uid()))
                .addValue("classCode", b.classCode())
                .addValue("venue", b.venue())
                .addValue("venueRaw", s.venueRaw())
                .addValue("currency", b.quoteCurrency())
                .addValue("lot", Math.max(1, s.lot()))
                .addValue("minPriceIncrement", s.minPriceIncrement())
                .addValue("buyAvailable", s.buyAvailable())
                .addValue("sellAvailable", s.sellAvailable())
                .addValue("apiTradeAvailable", s.apiTradeAvailable())
                .addValue("shortEnabled", s.shortEnabled())
                .addValue("weekendTrading", s.weekendTrading())
                .addValue("tradingStatus", s.tradingStatus())
                .addValue("basicAsset", s.basicAsset())
                .addValue("expiresAt", s.expiresAt() == null ? null : Timestamp.from(s.expiresAt()), Types.TIMESTAMP)
                .addValue("strikePrice", s.strikePrice())
                .addValue("syncedAt", Timestamp.from(syncedAt))
                .addValue("now", now);
    }

    /**
     * ticker и name объявлены NOT NULL. Если брокер не дал ни того, ни другого — подставляем uid:
     * инструмент хотя бы останется находимым, а батч не упадёт из-за одной кривой строки.
     */
    private static String nvl(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
