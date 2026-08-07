package ru.larionov.backend.money;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Нормализация кодов валют и стейблкоиновые пеги.
 *
 * <h3>Про пег</h3>
 * USDT и USDC считаются равными доллару один к одному. Это ДОПУЩЕНИЕ, а не рыночный
 * факт: у стейблкоинов бывает отклонение от привязки, а в кризис — и заметное. Держим
 * его здесь одной константой, чтобы оно было видно и заменяемо, а не расползалось
 * по коду безымянными единицами.
 *
 * Для нашей задачи допущение уместно: конвертация участвует ТОЛЬКО в показе сводного
 * баланса. Ни размер заявки, ни бюджет, ни риск-лимиты её не читают, поэтому даже
 * заметный депег исказит подпись под числом, но не решение бота.
 */
public final class CurrencyCode {

    public static final String RUB = "RUB";
    public static final String USD = "USD";

    /** Валюты, приравненные к доллару. */
    private static final Set<String> USD_PEGGED = Set.of("USDT", "USDC", "USD");

    /** Символы для человека. */
    private static final Map<String, String> SYMBOLS = Map.of(
            "RUB", "₽",
            "USD", "$",
            "USDT", "$",
            "USDC", "$",
            "EUR", "€"
    );

    private CurrencyCode() {
    }

    /** Верхний регистр без пробелов; null и пустая строка остаются null. */
    public static String normalize(String currency) {
        if (currency == null) {
            return null;
        }
        String trimmed = currency.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    public static boolean isUsdPegged(String currency) {
        String code = normalize(currency);
        return code != null && USD_PEGGED.contains(code);
    }

    /**
     * Валюта, к которой приводится код для поиска курса.
     * USDT и USDC схлопываются в USD — курса «USDT к рублю» нам искать не нужно.
     */
    public static String pegBase(String currency) {
        return isUsdPegged(currency) ? USD : normalize(currency);
    }

    /** Одна и та же валюта с точностью до пега. */
    public static boolean sameMoney(String left, String right) {
        String a = pegBase(left);
        String b = pegBase(right);
        return a != null && a.equals(b);
    }

    public static String symbol(String currency) {
        String code = normalize(currency);
        return code == null ? "" : SYMBOLS.getOrDefault(code, code);
    }
}
