package ru.larionov.backend.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * Деньги для человека: уведомления в Telegram и тексты событий.
 *
 * Отдельно от расчётов намеренно: в книге и в стратегии деньги живут неокруглёнными,
 * а округление — исключительно вопрос показа.
 */
public final class MoneyFormat {

    private static final Map<String, String> SYMBOLS = Map.of(
            "RUB", "₽",
            "USD", "$",
            "EUR", "€"
    );

    private MoneyFormat() {
    }

    /** Например, «10 000,25 ₽». Null — прочерк. */
    public static String money(BigDecimal value, String currency) {
        if (value == null) {
            return "—";
        }
        return format(value, false) + suffix(currency);
    }

    /** То же, но со знаком для положительных: «+12,34 ₽». Для P/L знак важен. */
    public static String signed(BigDecimal value, String currency) {
        if (value == null) {
            return "—";
        }
        return format(value, true) + suffix(currency);
    }

    private static String format(BigDecimal value, boolean withPlus) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');

        DecimalFormat format = new DecimalFormat("#,##0.##", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);

        String text = format.format(value);
        return withPlus && value.signum() > 0 ? "+" + text : text;
    }

    private static String suffix(String currency) {
        if (currency == null || currency.isBlank()) {
            return "";
        }
        String code = currency.toUpperCase(Locale.ROOT);
        return " " + SYMBOLS.getOrDefault(code, code);
    }
}
