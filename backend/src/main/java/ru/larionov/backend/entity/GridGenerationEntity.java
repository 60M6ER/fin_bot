package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.GenerationKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Отрезок жизни бота: либо одно поколение сетки от постановки до перестановки,
 * либо восстановительный эпизод со своим результатом.
 *
 * Денег здесь нет намеренно — только границы окна в книге операций. Циклы и P/L
 * считаются из money_ledger по этому окну, поэтому уточнение сумм ремонтным
 * проходом учёта автоматически доезжает и до статистики.
 *
 * Окна сеточной и восстановительной строк ПЕРЕСЕКАЮТСЯ, когда плечо работает
 * одновременно с сеткой. Поэтому одного окна для разделения денег мало: внутри
 * него строки книги дополнительно делятся по назначению — см. GridGenerationService.
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
                columnNames = {"bot_id", "dry_run", "generation", "kind"}
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

    /**
     * Что это за отрезок: поколение сетки или восстановительный эпизод.
     * Входит в ключ уникальности — у одного поколения законно бывают обе строки.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    @Builder.Default
    private GenerationKind kind = GenerationKind.GRID;

    /**
     * Направление сетки этого отрезка. У восстановительного эпизода — направление плеча.
     *
     * Строкой, а не перечислением, — по тому же соображению, что и соседний origin:
     * это архивная запись о том, чем бот торговал тогда, и переименование константы
     * в коде не должно превращать историю в нечитаемую.
     */
    @Column(name = "direction", nullable = false, length = 8)
    @Builder.Default
    private String direction = "LONG";

    /** Торговался ли отрезок с плечом. Подпись в таблице поколений. */
    @Column(name = "margin", nullable = false)
    private boolean margin;

    /** Null у восстановительного эпизода: диапазона у него нет. */
    @Column(name = "lower_price", precision = 24, scale = 9)
    private BigDecimal lowerPrice;

    @Column(name = "upper_price", precision = 24, scale = 9)
    private BigDecimal upperPrice;

    @Column(name = "levels")
    private Integer levels;

    @Column(name = "origin", length = 32)
    private String origin;

    /** Цена, по которой открыто плечо. Только у восстановительного эпизода. */
    @Column(name = "entry_price", precision = 24, scale = 9)
    private BigDecimal entryPrice;

    /** Расчётная цена закрытия в безубыток. Только у восстановительного эпизода. */
    @Column(name = "target_price", precision = 24, scale = 9)
    private BigDecimal targetPrice;

    @Column(name = "multiplier", precision = 12, scale = 4)
    private BigDecimal multiplier;

    /**
     * Связывает строку с заявками своего эпизода. Именно по нему деньги эпизода
     * отделяются от денег сетки, когда их окна в книге пересекаются.
     */
    @Column(name = "margin_episode_id")
    private UUID marginEpisodeId;

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
