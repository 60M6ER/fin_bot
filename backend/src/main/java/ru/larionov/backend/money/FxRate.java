package ru.larionov.backend.money;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Курс одной валюты к другой вместе с происхождением.
 *
 * Источник и время — не украшение: курс ЦБ на вчерашнюю дату и биржевой курс минуту
 * назад дают разные числа, и пользователь имеет право видеть, каким из них посчитан
 * его баланс. Без этой подписи сводная цифра выглядела бы фактом биржи, каковым она
 * не является.
 *
 * @param rate сколько единиц {@code quote} стоит одна единица {@code base}
 */
public record FxRate(
        String base,
        String quote,
        BigDecimal rate,
        String source,
        Instant asOf
) {

    public FxRate {
        base = CurrencyCode.normalize(base);
        quote = CurrencyCode.normalize(quote);
        if (rate == null || rate.signum() <= 0) {
            throw new IllegalArgumentException("Курс должен быть положительным: " + rate);
        }
    }

    /** Единица: валюта сама к себе. */
    public static FxRate identity(String currency) {
        return new FxRate(currency, currency, BigDecimal.ONE, "IDENTITY", Instant.now());
    }

    public FxRate inverted() {
        return new FxRate(quote, base,
                BigDecimal.ONE.divide(rate, 18, java.math.RoundingMode.HALF_UP), source, asOf);
    }
}
