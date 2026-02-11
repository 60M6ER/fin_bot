package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.BotStatus;
import ru.larionov.backend.enums.StrategyType;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bot")
public class BotEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exchange_connection_id", nullable = false)
    private ExchangeConnectionEntity exchangeConnection;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    /**
     * Храним JSON как строку (быстрый старт).
     * В БД колонка jsonb; строка должна быть валидным JSON.
     */
    @Column(name = "strategy_config", nullable = false, columnDefinition = "jsonb")
    private String strategyConfig;

    @Column(name = "symbol", nullable = false, length = 64)
    private String symbol;

    @Column(name = "timeframe", length = 32)
    private String timeframe;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BotStatus status;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

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

        if (strategyConfig == null || strategyConfig.isBlank()) {
            strategyConfig = "{}";
        }
        if (status == null) {
            status = BotStatus.DRAFT;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (strategyConfig == null || strategyConfig.isBlank()) {
            strategyConfig = "{}";
        }
    }
}

