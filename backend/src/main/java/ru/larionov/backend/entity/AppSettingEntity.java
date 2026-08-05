package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "app_setting")
public class AppSettingEntity {

    @Id
    @Column(name = "setting_key", nullable = false, length = 128)
    private String key;

    @Column(name = "setting_value", columnDefinition = "text")
    private String value;

    /**
     * Секретные значения хранятся зашифрованными и никогда не отдаются наружу в открытом виде —
     * API возвращает только маску.
     */
    @Column(name = "is_secret", nullable = false)
    private boolean secret;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
