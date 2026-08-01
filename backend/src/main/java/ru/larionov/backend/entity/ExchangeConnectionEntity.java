package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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

    @Column(name = "api_key", columnDefinition = "text")
    private String apiKey;

    @Column(name = "api_secret", columnDefinition = "text")
    private String apiSecret;

    @Column(name = "passphrase", columnDefinition = "text")
    private String passphrase;

    @Column(name = "sandbox_enabled", nullable = false)
    private boolean sandboxEnabled;

    /**
     * Конкретный брокерский счёт, на котором торгуем.
     * Раньше код брал accounts.get(0) — для реальных денег это недопустимо.
     */
    @Column(name = "account_id", length = 64)
    private String accountId;

    /** Пер-биржевые параметры (ставка комиссии, настройки стримов) как JSON-строка. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private String settings;

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
        normalizeSettings();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        normalizeSettings();
    }

    private void normalizeSettings() {
        if (settings == null || settings.isBlank()) {
            settings = "{}";
        }
    }
}
