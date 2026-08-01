package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;

import java.time.Instant;
import java.util.UUID;

/** Событие бота: одна запись питает консоль, Telegram и ленту в UI. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "bot_event")
public class BotEventEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "bot_id", nullable = false)
    private UUID botId;

    @Column(name = "ts", nullable = false)
    private Instant ts;

    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 8)
    private BotEventLevel level;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private BotEventType type;

    @Column(name = "message", columnDefinition = "text")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (ts == null) ts = Instant.now();
        if (payload == null || payload.isBlank()) payload = "{}";
    }
}
