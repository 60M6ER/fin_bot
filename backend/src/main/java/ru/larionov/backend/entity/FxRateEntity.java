package ru.larionov.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Последний известный курс валютной пары.
 *
 * Живёт в БД, а не только в памяти, ради одного сценария: приложение перезапустили,
 * а сети до ЦБ в этот момент нет. Без сохранённого значения сводный баланс на экране
 * превратился бы в прочерк на ровном месте. Возраст курса при этом виден
 * пользователю — устаревшее число честнее отсутствующего, но только пока оно
 * подписано датой.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "fx_rate")
public class FxRateEntity {

    /** Пара в виде «USD/RUB»: пар у нас единицы, отдельный суррогатный ключ не нужен. */
    @Id
    @Column(name = "pair", nullable = false, length = 16)
    private String pair;

    @Column(name = "rate", nullable = false, precision = 24, scale = 10)
    private BigDecimal rate;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** Момент, к которому относится курс, а не момент записи. */
    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public static String pairOf(String base, String quote) {
        return base + "/" + quote;
    }
}
