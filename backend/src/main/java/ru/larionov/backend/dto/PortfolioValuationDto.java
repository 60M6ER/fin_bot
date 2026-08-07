package ru.larionov.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Сколько стоит всё хозяйство разом — по всем подключениям и всем валютам.
 *
 * Ровно тот вопрос, ради которого затевался валютный слой: боты на T-Invest считают
 * в рублях, боты на Poloniex — в USDT, и без общей цифры их не сравнить.
 *
 * @param byCurrency             баланс по каждой валюте как есть, без конвертации.
 *                               Это достоверная часть: она не зависит от курса
 * @param totalInDisplayCurrency всё вместе по курсу; null — курс неизвестен
 * @param incomplete             часть денег в свод не попала: нет цены, нет бюджета
 *                               либо неизвестен курс одной из валют
 * @param fxSource               чем посчитан курс: CBR, T_INVEST
 * @param fxAsOf                 на какой момент курс — по нему видно, что итог
 *                               посчитан вчерашним значением
 */
public record PortfolioValuationDto(
        Map<String, BigDecimal> byCurrency,
        BigDecimal totalInDisplayCurrency,
        String displayCurrency,
        int connectionCount,
        int botCount,
        boolean incomplete,
        String fxSource,
        Instant fxAsOf
) {

    public PortfolioValuationDto {
        byCurrency = byCurrency == null ? Map.of() : Map.copyOf(byCurrency);
    }
}
