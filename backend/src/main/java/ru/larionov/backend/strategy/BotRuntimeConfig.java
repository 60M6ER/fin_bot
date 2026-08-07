package ru.larionov.backend.strategy;

// Jackson 3 переиспользует аннотации из com.fasterxml.jackson.annotation:
// новый пакет tools.jackson содержит только databind/core.
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/**
 * Общая часть конфигурации любого бота — то, что нужно движку независимо от стратегии.
 * Специфичные поля (границы сетки, число уровней) разбирает сама стратегия из того же JSON.
 *
 * Неизвестные поля игнорируются намеренно: один и тот же JSON читают и движок, и стратегия.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BotRuntimeConfig(
        String instrumentUid,
        /*
         * Boolean, а не boolean: Jackson отказывается класть отсутствующее поле
         * в примитив, и бот со свежим конфигом {} падал бы с невнятной ошибкой
         * десериализации вместо понятного «не задан инструмент».
         */
        Boolean dryRun,
        BigDecimal maxCapital,
        /** Потолок позиции в ЕДИНИЦАХ БАЗОВОГО АКТИВА (был в лотах, пересчитан миграцией 701). */
        BigDecimal maxPositionQuantity,
        Integer maxOrdersPerDay,
        Integer maxOrdersPerMinute,
        Integer tickIntervalSeconds,
        PriceSource priceSource
) {

    public enum PriceSource {
        /** Поток последних цен: событий немного, для сетки достаточно. */
        LAST_PRICE,
        /** Стакан: точнее (виден спред), но событий на порядок больше. */
        ORDER_BOOK
    }

    public BotRuntimeConfig {
        if (dryRun == null) {
            // Отсутствие флага означает живую торговлю — как и решено по режиму запуска.
            dryRun = false;
        }
        if (tickIntervalSeconds == null || tickIntervalSeconds <= 0) {
            tickIntervalSeconds = 60;
        }
        if (priceSource == null) {
            priceSource = PriceSource.LAST_PRICE;
        }
        // Дефолты лимитов намеренно НЕ задаём: отсутствие лимита должно быть
        // осознанным решением пользователя, а не следствием забытого поля.
    }

    public boolean hasInstrument() {
        return instrumentUid != null && !instrumentUid.isBlank();
    }
}
