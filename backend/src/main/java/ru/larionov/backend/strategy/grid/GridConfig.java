package ru.larionov.backend.strategy.grid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Параметры сетки.
 *
 * Поля движка (instrumentUid, лимиты, dryRun) живут в том же JSON и разбираются
 * отдельно через BotRuntimeConfig — здесь только то, что нужно самой стратегии.
 *
 * Подключение сюда НЕ входит: связь бота с подключением хранится колонкой
 * bot.exchange_connection_id, чтобы не было двух расходящихся источников правды.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GridConfig(
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        /*
         * Обёртки, а не примитивы: Jackson не умеет класть отсутствующее поле
         * в примитив и падает с ошибкой десериализации вместо понятного сообщения
         * о том, какого параметра не хватает.
         */
        Integer levels,
        Long lotsPerOrder,
        Integer maxActiveOrders,
        RangeExitAction onRangeExit,
        BigDecimal minStepToCommissionRatio,
        Boolean enabled
) {

    public enum RangeExitAction {
        /** Перестать покупать, уже купленное продолжать продавать. Позиция замирает. */
        STOP_BUYING,
        /** Снять все заявки и остановить бота. Жёстче, но предсказуемее. */
        CANCEL_AND_STOP
    }

    public GridConfig {
        if (lowerPrice == null || upperPrice == null) {
            throw new IllegalArgumentException("lowerPrice и upperPrice обязательны");
        }
        if (lowerPrice.signum() <= 0) {
            throw new IllegalArgumentException("lowerPrice должен быть больше нуля");
        }
        if (upperPrice.compareTo(lowerPrice) <= 0) {
            throw new IllegalArgumentException("upperPrice должен быть больше lowerPrice");
        }
        if (levels == null || levels <= 0) {
            throw new IllegalArgumentException("levels обязателен и должен быть больше нуля");
        }
        if (lotsPerOrder == null || lotsPerOrder <= 0) {
            throw new IllegalArgumentException("lotsPerOrder обязателен и должен быть больше нуля");
        }
        if (maxActiveOrders == null || maxActiveOrders <= 0) {
            maxActiveOrders = levels;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (onRangeExit == null) {
            onRangeExit = RangeExitAction.STOP_BUYING;
        }
        // Требуемый запас шага над комиссией за оборот. 1.5 = «шаг окупает комиссию
        // минимум в полтора раза». Меньше 1.0 — торговля в убыток по построению.
        if (minStepToCommissionRatio == null || minStepToCommissionRatio.signum() <= 0) {
            minStepToCommissionRatio = new BigDecimal("1.5");
        }
    }

    /** Шаг сетки до округления к шагу цены инструмента. */
    public BigDecimal rawStep() {
        return upperPrice.subtract(lowerPrice)
                .divide(BigDecimal.valueOf(levels), 9, RoundingMode.HALF_UP);
    }
}
