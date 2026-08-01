package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 64)
    private StrategyType strategyType;

    /**
     * Подключение, на котором работает бот.
     * Единственный источник правды о связи: в strategy_config его дублировать нельзя,
     * иначе каскад запуска и конфиг стратегии разъедутся.
     */
    @Column(name = "exchange_connection_id", nullable = false)
    private UUID exchangeConnectionId;

    /**
     * Храним JSON как строку (быстрый старт).
     * В БД колонка jsonb; строка должна быть валидным JSON.
     *
     * JdbcTypeCode(JSON) обязателен: без него Hibernate биндит String как varchar,
     * и Postgres отвергает вставку в jsonb без явного каста.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "strategy_config", nullable = false, columnDefinition = "jsonb")
    private String strategyConfig;

    @Column(name = "is_active", nullable = false)
    private boolean active;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        if (strategyConfig == null || strategyConfig.isBlank()) {
            strategyConfig = "{}";
        }
    }
}
