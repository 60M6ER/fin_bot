package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Отрезок жизни сетки: один диапазон от постановки до перестановки.
 *
 * Денег здесь нет намеренно — только границы окна в книге операций. Циклы и P/L
 * поколения считаются из money_ledger по этому окну, поэтому уточнение сумм
 * ремонтным проходом учёта автоматически доезжает и до статистики поколения.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "grid_generation",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_grid_generation_bot_generation",
                columnNames = {"bot_id", "dry_run", "generation"}
        )
)
public class GridGenerationEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "generation", nullable = false)
    private long generation;

    @Column(name = "lower_price", nullable = false, precision = 24, scale = 9)
    private BigDecimal lowerPrice;

    @Column(name = "upper_price", nullable = false, precision = 24, scale = 9)
    private BigDecimal upperPrice;

    @Column(name = "levels")
    private Integer levels;

    @Column(name = "origin", length = 32)
    private String origin;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null — поколение действующее. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Строки поколения в книге: seq > startSeq и seq <= endSeq (или без верхней границы). */
    @Column(name = "start_seq", nullable = false)
    private long startSeq;

    @Column(name = "end_seq")
    private Long endSeq;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (startedAt == null) {
            startedAt = Instant.now();
        }
    }
}
