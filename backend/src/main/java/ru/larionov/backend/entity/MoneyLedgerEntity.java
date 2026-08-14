package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                columnNames = {"order_id", "entry_type", "executed_quantity_cum"}
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

    /**
     * Назначение записи. Продажа пыли расходует не партии сетки, а корзину пыли:
     * без этого признака перестроение инвентаря списало бы стоимость не оттуда.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 16)
    @Builder.Default
    private OrderPurpose purpose = OrderPurpose.GRID;

    /**
     * Открыла запись партию или закрыла.
     *
     * Именно по этому признаку восстанавливаются партии: в лонге партию открывает
     * покупка, в шорте — продажа, и вывести одно из другого без направления нельзя.
     * Направление же принадлежит поколению, а книга живёт через поколения, поэтому
     * роль записывается в момент сделки и больше не пересчитывается.
     *
     * Неторговые отметки (перестановка сетки, изменение бюджета) роли не имеют.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "grid_role", length = 8)
    private GridRole gridRole;

    /** Количество в ЕДИНИЦАХ БАЗОВОГО АКТИВА. Деньги строки = price × quantity. */
    @Column(name = "quantity", precision = 28, scale = 10)
    private BigDecimal quantity;

    /**
     * Заявочная единица биржи, действовавшая на момент записи.
     *
     * В расчётах БОЛЬШЕ НЕ УЧАСТВУЕТ: с количеством в единицах базового актива
     * сумма считается как price × quantity, без множителей. Оставлена как след для
     * разбора инцидентов — по ней видно, из какой лотности выросла строка.
     */
    @Column(name = "exchange_lot_size", nullable = false, precision = 28, scale = 10)
    private BigDecimal exchangeLotSize;

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

    /**
     * Накопленное исполнение ордера на момент строки — часть уникального ключа.
     *
     * PostgreSQL сравнивает numeric без учёта хвостовых нулей, поэтому 1 и 1.0000000000
     * для уникального ключа одно и то же значение, и защита от дубля между стримом
     * и сверкой переживает переход на дробное количество.
     */
    @Column(name = "executed_quantity_cum", precision = 28, scale = 10)
    private BigDecimal executedQuantityCum;

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
        // Роль по стороне для тех, кто её не указал, — то же лонговое правило,
        // которым миграция заполняет накопленную историю. См. BotOrderEntity.roleFromSide.
        if (gridRole == null && side != null) {
            gridRole = BotOrderEntity.roleFromSide(side);
        }
        if (exchangeLotSize == null || exchangeLotSize.signum() <= 0) {
            exchangeLotSize = BigDecimal.ONE;
        }
        if (commission == null) {
            commission = BigDecimal.ZERO;
        }
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        // Единая шкала количеств: уникальный ключ и сравнения не должны зависеть
        // от того, с каким масштабом BigDecimal пришёл от вызывающего кода.
        quantity = scale(quantity);
        executedQuantityCum = scale(executedQuantityCum);
        exchangeLotSize = scale(exchangeLotSize);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null
                ? null
                : value.setScale(BotOrderEntity.QUANTITY_SCALE, RoundingMode.HALF_UP);
    }
}
