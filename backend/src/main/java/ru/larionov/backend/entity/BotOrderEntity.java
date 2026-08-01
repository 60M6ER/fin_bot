package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Запись журнала ордеров.
 *
 * Строка создаётся ДО сетевого вызова со статусом PENDING — это и делает постановку
 * идемпотентной: если ответ не пришёл, ордер всё равно известен нам по clientOrderId,
 * и сверка выяснит его судьбу вместо того, чтобы выставлять второй такой же.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bot_order")
public class BotOrderEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Column(name = "instrument_uid", nullable = false, length = 64)
    private String instrumentUid;

    /** Наш идентификатор. Уходит в PostOrderRequest.orderId и возвращается в событиях стрима. */
    @Column(name = "client_order_id", nullable = false, length = 36, unique = true)
    private String clientOrderId;

    @Column(name = "exchange_order_id", length = 64)
    private String exchangeOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    private OrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderStatus status;

    /** Уровень сетки, к которому относится ордер. Для не-сеточных стратегий null. */
    @Column(name = "grid_level")
    private Integer gridLevel;

    @Column(name = "requested_lots", nullable = false)
    private long requestedLots;

    @Column(name = "executed_lots", nullable = false)
    private long executedLots;

    @Column(name = "limit_price", precision = 24, scale = 9)
    private BigDecimal limitPrice;

    @Column(name = "avg_price", precision = 24, scale = 9)
    private BigDecimal avgPrice;

    @Column(name = "fee", precision = 24, scale = 9)
    private BigDecimal fee;

    /** Ордер бумажного режима: в журнале лежит рядом с живыми, но на бирже его нет. */
    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** Сколько лотов ещё не исполнено. */
    public long remainingLots() {
        return Math.max(0, requestedLots - executedLots);
    }
}
