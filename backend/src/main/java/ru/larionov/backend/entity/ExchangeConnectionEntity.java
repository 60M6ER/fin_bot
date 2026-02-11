package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import ru.larionov.backend.enums.ExchangeType;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "exchange_connection")
public class ExchangeConnectionEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "exchange", nullable = false, length = 32)
    private ExchangeType exchange;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "api_key", nullable = false, columnDefinition = "text")
    private String apiKey;

    @Column(name = "api_secret", nullable = false, columnDefinition = "text")
    private String apiSecret;

    @Column(name = "passphrase", columnDefinition = "text")
    private String passphrase;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
