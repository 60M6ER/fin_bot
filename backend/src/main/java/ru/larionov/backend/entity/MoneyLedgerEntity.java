package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "money_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_money_ledger_order_type_cum",
                columnNames = {"order_id", "entry_type", "executed_lots_cum"}
        )
)
public class MoneyLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seq", nullable = false)
    private Long seq;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 32)
    private LedgerEntryType entryType;

    @Column(name = "affects_cash", nullable = false)
    private boolean affectsCash;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "client_order_id", length = 36)
    private String clientOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", length = 8)
    private OrderSide side;

    @Column(name = "grid_level")
    private Integer gridLevel;

    @Column(name = "lots")
    private Long lots;

    @Column(name = "lot_size", nullable = false)
    private int lotSize;

    @Column(name = "price", precision = 24, scale = 9)
    private BigDecimal price;

    @Column(name = "gross_amount", precision = 24, scale = 9)
    private BigDecimal grossAmount;

    @Column(name = "commission", precision = 24, scale = 9)
    private BigDecimal commission;

    @Column(name = "commission_estimated", nullable = false)
    private boolean commissionEstimated;

    @Column(name = "amount", precision = 24, scale = 9)
    private BigDecimal amount;

    @Column(name = "executed_lots_cum")
    private Long executedLotsCum;

    @Column(name = "currency", length = 16)
    private String currency;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @PrePersist
    void prePersist() {
        if (ts == null) {
            ts = Instant.now();
        }
        if (lotSize <= 0) {
            lotSize = 1;
        }
        if (commission == null) {
            commission = BigDecimal.ZERO;
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
    }
}
