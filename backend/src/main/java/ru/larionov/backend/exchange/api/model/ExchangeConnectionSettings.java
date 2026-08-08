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
 * @param baseCurrency РАСЧЁТНАЯ валюта подключения: та, в которой имеет смысл его
 *                     баланс. У брокера это рубль, у Poloniex — USDT. Всё остальное
 *                     на счёте — товар, и в баланс попадает по текущей цене.
 *                     <p>
 *                     Валюту приходится задавать, а не выводить: «та, которой больше»
 *                     ошибается на счёте с пылью из десятка монет, а «валюта первого
 *                     бота» — на подключении, где боты торгуют разные пары. null
 *                     означает «спросить у биржи её основную расчётную валюту»,
 *                     и для обеих поддержанных бирж этот ответ верен.
 */
public record ExchangeConnectionSettings(
        BigDecimal commissionRate,
        Integer streamInactivityTimeoutSec,
        Integer streamPingDelayMs,
        Integer maxMarketDataStreamsCount,
        String baseCurrency
) {

    /** Тариф «Инвестор» у Т-Инвестиций — самый дорогой из массовых. */
    public static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.003");

    public static ExchangeConnectionSettings defaults() {
        return new ExchangeConnectionSettings(DEFAULT_COMMISSION_RATE, null, null, null, null);
    }

    public ExchangeConnectionSettings {
        if (commissionRate == null) {
            commissionRate = DEFAULT_COMMISSION_RATE;
        }
        if (commissionRate.signum() < 0) {
            throw new IllegalArgumentException("commissionRate must not be negative");
        }
        if (baseCurrency != null) {
            baseCurrency = baseCurrency.isBlank()
                    ? null
                    : baseCurrency.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** Комиссия за полный оборот: купили и продали. Именно с ней сравнивается шаг сетки. */
    public BigDecimal roundTripCommissionRate() {
        return commissionRate.multiply(BigDecimal.valueOf(2));
    }
}
