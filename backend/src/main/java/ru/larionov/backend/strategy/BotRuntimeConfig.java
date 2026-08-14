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
        PriceSource priceSource,
        /*
         * Разрешена ли боту короткая позиция. Тот же ключ JSON читает и GridConfig:
         * это одна настройка, просто у движка и у стратегии на неё разные виды.
         * Хранить её дважды нельзя — разошлись бы.
         */
        Boolean marginEnabled,
        /*
         * Потолки короткой позиции — в единицах актива и в деньгах. Живут здесь,
         * рядом с maxCapital и maxPositionQuantity: это лимиты движка, а не геометрия
         * сетки, и проверяет их риск-контроль, до которого стратегия не дотягивается.
         *
         * Умолчаний нет намеренно, как и у остальных лимитов. Но в отличие от них
         * отсутствие потолка шорта не означает «без ограничения»: риск-контроль
         * такую заявку отвергнет. Убыток по короткой позиции сверху не ограничен
         * ничем, и «забыл задать» не должно означать «разрешено сколько угодно».
         */
        BigDecimal maxShortQuantity,
        BigDecimal maxShortNotional,
        /*
         * Разрешает маржинальные операции ЖИВЬЁМ, а не только на бумаге.
         *
         * Отдельный рубильник поверх marginEnabled, и выключен по умолчанию. Причина
         * не в перестраховке: шортовая сетка и переворот позиции ни разу не работали
         * на настоящем рынке. Тестами проверена арифметика и все отказы, но не
         * поведение брокера — как исполняются заявки на открытии после ночи, во что
         * на деле обходится перенос, что показывают маржинальные показатели под
         * нагрузкой. Ответы на это даёт только бумажный прогон, и пока его не было,
         * умолчание обязано быть осторожным.
         *
         * Включать осознанно и с маленькими потолками: первая живая непокрытая
         * позиция должна быть такого размера, который не жаль потерять целиком.
         */
        Boolean allowLiveMargin
) {

    /** Прежняя форма — для тестов и кода, не знающего про маржу. */
    public BotRuntimeConfig(String instrumentUid, Boolean dryRun, BigDecimal maxCapital,
                            BigDecimal maxPositionQuantity, Integer maxOrdersPerDay,
                            Integer maxOrdersPerMinute, Integer tickIntervalSeconds,
                            PriceSource priceSource) {
        this(instrumentUid, dryRun, maxCapital, maxPositionQuantity, maxOrdersPerDay,
                maxOrdersPerMinute, tickIntervalSeconds, priceSource, null, null, null, null);
    }

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
