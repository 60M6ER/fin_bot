package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /**
     * Количество в ЕДИНИЦАХ БАЗОВОГО АКТИВА (штуки бумаг, монеты), а не в лотах биржи.
     * Пересчёт в заявочные единицы биржи — дело гейтвея, он знает {@code exchangeLotSize}.
     */
    @Column(name = "requested_quantity", nullable = false, precision = 28, scale = 10)
    private BigDecimal requestedQuantity;

    @Column(name = "executed_quantity", nullable = false, precision = 28, scale = 10)
    private BigDecimal executedQuantity;

    @Column(name = "limit_price", precision = 24, scale = 9)
    private BigDecimal limitPrice;

    @Column(name = "avg_price", precision = 24, scale = 9)
    private BigDecimal avgPrice;

    @Column(name = "fee", precision = 24, scale = 9)
    private BigDecimal fee;

    @Column(name = "fee_actual", nullable = false)
    private boolean feeActual;

    @Column(name = "fee_rate", precision = 24, scale = 9)
    private BigDecimal feeRate;

    @Column(name = "fee_source", length = 32)
    private String feeSource;

    @Column(name = "fee_currency", length = 16)
    private String feeCurrency;

    /**
     * Сколько единиц базового актива в одной заявочной единице биржи на момент постановки.
     * Дробный, потому что у криптобирж заявочная единица — сама монета, а шаг количества
     * бывает 0.000001. Хранится в журнале, чтобы финансовая запись не зависела от будущей
     * правки справочника.
     */
    @Column(name = "exchange_lot_size", nullable = false, precision = 28, scale = 10)
    private BigDecimal exchangeLotSize;

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
        normalizeQuantities();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        normalizeQuantities();
        updatedAt = Instant.now();
    }

    /**
     * Единая шкала у всех количеств. Без этого одно и то же число уезжало бы в базу
     * то как 1, то как 1.0000000000 — читается одинаково, но сравнения и уникальные
     * ключи по numeric становятся полем для сюрпризов.
     */
    private void normalizeQuantities() {
        if (executedQuantity == null) executedQuantity = BigDecimal.ZERO;
        if (exchangeLotSize == null || exchangeLotSize.signum() <= 0) exchangeLotSize = BigDecimal.ONE;
        requestedQuantity = scale(requestedQuantity);
        executedQuantity = scale(executedQuantity);
        exchangeLotSize = scale(exchangeLotSize);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    public static final int QUANTITY_SCALE = 10;

    /** Сколько ещё не исполнено. Никогда не отрицательно. */
    public BigDecimal remainingQuantity() {
        BigDecimal remaining = nvl(requestedQuantity).subtract(nvl(executedQuantity));
        return remaining.signum() < 0 ? BigDecimal.ZERO : remaining;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
