package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Строка справочника инструментов.
 *
 * Справочник живёт в БД, а не в памяти, по двум причинам: поиск в форме бота должен
 * работать сразу после рестарта и не требовать поднятого подключения к брокеру,
 * а выгрузка всей вселенной инструментов по gRPC занимает секунды — на каждое
 * нажатие клавиши так ходить нельзя.
 *
 * Ключ — (exchange, instrument_uid). figi не годится: у опционов T-Invest его просто нет.
 * ticker + class_code тоже не годятся: они не уникальны между площадками и не существуют
 * у криптобирж. uid есть у всех типов и уже используется всей системой
 * (bot.strategy_config.instrumentUid, bot_order.instrument_uid).
 *
 * Делистинг помечается флагом, а не удалением строки: на инструмент может ссылаться
 * конфиг существующего бота, и подпись для него всё равно нужно уметь показать.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "instrument")
public class InstrumentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange", nullable = false, length = 32)
    private ExchangeType exchange;

    @Column(name = "instrument_uid", nullable = false, length = 64)
    private String instrumentUid;

    @Column(name = "figi", length = 32)
    private String figi;

    @Column(name = "isin", length = 32)
    private String isin;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 24)
    private InstrumentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", nullable = false, length = 16)
    private MarketSegment segment;

    @Column(name = "ticker", nullable = false, length = 64)
    private String ticker;

    @Column(name = "name", nullable = false, length = 512)
    private String name;

    @Column(name = "class_code", length = 32)
    private String classCode;

    /** Нормализованная площадка для показа и фильтра: MOEX / SPB / FORTS / OTC / DEALER. */
    @Column(name = "venue", length = 32)
    private String venue;

    /** Сырая строка брокера. В поиске не участвует, нужна для разбора инцидентов. */
    @Column(name = "venue_raw", length = 64)
    private String venueRaw;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "lot", nullable = false)
    private int lot;

    @Column(name = "min_price_increment", precision = 24, scale = 9)
    private BigDecimal minPriceIncrement;

    @Column(name = "buy_available", nullable = false)
    private boolean buyAvailable;

    @Column(name = "sell_available", nullable = false)
    private boolean sellAvailable;

    @Column(name = "api_trade_available", nullable = false)
    private boolean apiTradeAvailable;

    @Column(name = "short_enabled", nullable = false)
    private boolean shortEnabled;

    @Column(name = "weekend_trading", nullable = false)
    private boolean weekendTrading;

    @Column(name = "trading_status", length = 64)
    private String tradingStatus;

    /** FUTURE/OPTION: базовый актив и экспирация. Для спота null. */
    @Column(name = "basic_asset", length = 64)
    private String basicAsset;

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Только OPTION. */
    @Column(name = "strike_price", precision = 24, scale = 9)
    private BigDecimal strikePrice;

    /** false = инструмент пропал из выгрузки биржи (делистинг или экспирация). */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (syncedAt == null) syncedAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
