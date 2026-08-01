package ru.larionov.backend.exchange.api.model;

import java.math.BigDecimal;

/**
 * Пер-биржевые параметры подключения (колонка exchange_connection.settings, jsonb).
 *
 * @param commissionRate ставка комиссии брокера за одну сторону сделки (0.0005 = 0.05%).
 *                       От неё зависит проверка безубытка шага сетки, поэтому дефолт
 *                       намеренно консервативный: завышенная комиссия заставит бота
 *                       отказаться от слишком тесной сетки, заниженная — молча пустить
 *                       в торговлю, которая не окупает издержки.
 * @param streamInactivityTimeoutSec сколько ждать событий, прежде чем считать стрим залипшим
 * @param streamPingDelayMs период ping'ов, которыми стрим удерживается живым
 * @param maxMarketDataStreamsCount потолок числа стримов рыночных данных на подключение
 */
public record ExchangeConnectionSettings(
        BigDecimal commissionRate,
        Integer streamInactivityTimeoutSec,
        Integer streamPingDelayMs,
        Integer maxMarketDataStreamsCount
) {

    /** Тариф «Инвестор» у Т-Инвестиций — самый дорогой из массовых. */
    public static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.003");

    public static ExchangeConnectionSettings defaults() {
        return new ExchangeConnectionSettings(DEFAULT_COMMISSION_RATE, null, null, null);
    }

    public ExchangeConnectionSettings {
        if (commissionRate == null) {
            commissionRate = DEFAULT_COMMISSION_RATE;
        }
        if (commissionRate.signum() < 0) {
            throw new IllegalArgumentException("commissionRate must not be negative");
        }
    }

    /** Комиссия за полный оборот: купили и продали. Именно с ней сравнивается шаг сетки. */
    public BigDecimal roundTripCommissionRate() {
        return commissionRate.multiply(BigDecimal.valueOf(2));
    }
}
