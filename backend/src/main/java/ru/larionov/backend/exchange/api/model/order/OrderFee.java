package ru.larionov.backend.exchange.api.model.order;

import java.math.BigDecimal;

/**
 * Нормализованная комиссия ордера.
 *
 * Сторона сделки (BUY/SELL) живёт в OrderState, а здесь только денежное число,
 * его качество и источник. Так один и тот же объект подходит T-Invest, будущим
 * биржам и нашему fallback-расчёту по тарифной ставке.
 */
public record OrderFee(
        BigDecimal rate,          // например 0.003 или 0.0005, может быть null
        BigDecimal amount,        // сумма в валюте комиссии/инструмента
        boolean actual,           // true = подтверждённый факт, false = оценка
        CommissionSource source,
        String currency
) {
    public OrderFee {
        if (source == null) {
            source = actual ? CommissionSource.EXCHANGE_EXECUTED : CommissionSource.BROKER_RATE_ESTIMATE;
        }
    }

    public static OrderFee actual(BigDecimal amount, String currency, CommissionSource source) {
        return new OrderFee(null, amount, true, source, currency);
    }

    public static OrderFee estimated(BigDecimal rate, BigDecimal amount, String currency, CommissionSource source) {
        return new OrderFee(rate, amount, false, source, currency);
    }
}
