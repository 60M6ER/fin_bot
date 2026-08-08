package ru.larionov.backend.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Деньги подключения целиком: что распределено по ботам и что лежит без дела.
 *
 * <h3>Как это сходится</h3>
 * <pre>
 * total           = деньги счёта + всё прочее на нём по текущей цене
 * unallocatedCash = total − botsBalance
 * </pre>
 * {@code total} — сколько подключение стоит прямо сейчас, и собирается он так же,
 * как считает биржа: деньги плюс товар. «Деньги» включают заблокированное заявками:
 * заявку можно снять, деньги под ней никуда не делись.
 *
 * Раньше сходимость строилась наоборот — из свободных денег счёта вычитались
 * свободные деньги ботов, а итог собирался обратно. Обе части считались по-разному
 * (счёт не показывал денег под заявками, а бот считал их своими), и одни и те же
 * деньги терялись дважды: 08.08.2026 на Poloniex это дало «свободно −48.81 USDT»
 * при 118 на счёте и 125 розданных.
 *
 * <h3>Про валюты</h3>
 * Числовые поля выражены в {@code currency} — ОСНОВНОЙ валюте подключения (той,
 * в которой считает большинство его ботов). Раньше эта валюта просто бралась
 * у первого бота, а суммы складывались независимо от того, совпадают ли валюты, —
 * подключение с ботами в рублях и в USDT молча давало бессмысленное число.
 *
 * Теперь боты группируются по валютам: {@code byCurrency} показывает баланс каждой
 * группы как есть, а {@code totalInDisplayCurrency} сводит их вместе по курсу.
 * Конвертация участвует только здесь, в показе.
 *
 * <h3>Как читать null и минус</h3>
 * <ul>
 *   <li>{@code unallocatedCash} отрицателен — ботам роздано больше, чем есть свободных
 *       денег на счёте. Это не ошибка расчёта, а реальная перекладка бюджетов,
 *       и показать её важнее, чем спрятать;</li>
 *   <li>{@code incomplete} — часть ботов в сумму не попала (нет бюджета, нет цены
 *       либо неизвестен курс). Тогда суммы заведомо неполны;</li>
 *   <li>{@code currency == null} — валюту определить не удалось, денежные поля пусты;</li>
 *   <li>{@code totalInDisplayCurrency == null} — курс недоступен, свести валюты
 *       в одно число нечем. Разбивка при этом остаётся достоверной.</li>
 * </ul>
 */
public record ConnectionValuationDto(
        int botCount,
        /** Сколько ботов реально попало в сумму. */
        int valuedBotCount,
        BigDecimal allocatedBudget,
        BigDecimal botsBalance,
        BigDecimal botsPnl,
        BigDecimal freeCash,
        BigDecimal unallocatedCash,
        BigDecimal total,
        boolean incomplete,
        String currency,

        /** Баланс по каждой валюте подключения: код валюты → сумма. */
        Map<String, BigDecimal> byCurrency,
        /** Всё вместе в валюте показа; null — курс неизвестен. */
        BigDecimal totalInDisplayCurrency,
        String displayCurrency,
        /** Чем посчитан курс: CBR, T_INVEST, IDENTITY. */
        String fxSource,
        /** На какой момент курс. Позволяет увидеть, что число посчитано вчерашним. */
        java.time.Instant fxAsOf
) {

    public ConnectionValuationDto {
        byCurrency = byCurrency == null ? Map.of() : Map.copyOf(byCurrency);
    }

    public static ConnectionValuationDto empty() {
        return new ConnectionValuationDto(0, 0, null, null, null, null, null, null, false, null,
                Map.of(), null, null, null, null);
    }
}
