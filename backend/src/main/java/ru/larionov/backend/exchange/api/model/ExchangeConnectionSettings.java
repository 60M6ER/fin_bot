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
 * @param marginEnabled разрешена ли ботам этого подключения торговля с плечом.
 *                      <p>
 *                      Первый из двух рубильников: подключение РАЗРЕШАЕТ, бот
 *                      ВКЛЮЧАЕТ. Разделены намеренно — снятие галки здесь обязано
 *                      гасить маржинальный режим у всех ботов подключения разом,
 *                      не полагаясь на то, что каждого выключили по отдельности.
 *                      <p>
 *                      Завязка именно на галку, а не на свойства площадки: где
 *                      маржинальная торговля идёт отдельным рынком, признак биржи
 *                      ответа не даёт, а человек, заводивший подключение, — даёт.
 *                      <p>
 *                      По умолчанию выключена. Умолчание, включающее плечо, было бы
 *                      худшим из возможных: забытая настройка обязана делать бота
 *                      осторожнее, а не смелее.
 */
public record ExchangeConnectionSettings(
        BigDecimal commissionRate,
        Integer streamInactivityTimeoutSec,
        Integer streamPingDelayMs,
        Integer maxMarketDataStreamsCount,
        String baseCurrency,
        Boolean marginEnabled,
        /*
         * Тариф переноса непокрытой позиции через ночь. Отдельно от commissionRate
         * намеренно: та берётся с оборота в момент сделки, эта — с удержания,
         * посуточно. Сложить их в одно число нельзя, у них разная размерность.
         */
        CarryFeeSchedule uncoveredCarryFee
) {

    /** Тариф «Инвестор» у Т-Инвестиций — самый дорогой из массовых. */
    public static final BigDecimal DEFAULT_COMMISSION_RATE = new BigDecimal("0.003");

    public static ExchangeConnectionSettings defaults() {
        return new ExchangeConnectionSettings(
                DEFAULT_COMMISSION_RATE, null, null, null, null, null, null);
    }

    /** Настройки без маржинального признака: подключение, заведённое до его появления. */
    public ExchangeConnectionSettings(BigDecimal commissionRate,
                                      Integer streamInactivityTimeoutSec,
                                      Integer streamPingDelayMs,
                                      Integer maxMarketDataStreamsCount,
                                      String baseCurrency) {
        this(commissionRate, streamInactivityTimeoutSec, streamPingDelayMs,
                maxMarketDataStreamsCount, baseCurrency, null, null);
    }

    /** Настройки без тарифа переноса: подключение, заведённое до его появления. */
    public ExchangeConnectionSettings(BigDecimal commissionRate,
                                      Integer streamInactivityTimeoutSec,
                                      Integer streamPingDelayMs,
                                      Integer maxMarketDataStreamsCount,
                                      String baseCurrency,
                                      Boolean marginEnabled) {
        this(commissionRate, streamInactivityTimeoutSec, streamPingDelayMs,
                maxMarketDataStreamsCount, baseCurrency, marginEnabled, null);
    }

    public ExchangeConnectionSettings {
        if (marginEnabled == null) {
            marginEnabled = false;
        }
        if (uncoveredCarryFee == null) {
            uncoveredCarryFee = CarryFeeSchedule.defaults();
        }
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
