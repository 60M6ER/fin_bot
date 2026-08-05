package ru.larionov.backend.dto;

import java.math.BigDecimal;

/**
 * Деньги подключения целиком: что распределено по ботам и что лежит без дела.
 *
 * <h3>Как это сходится</h3>
 * <pre>
 * свободные деньги ботов = Σ (рабочий бюджет − себестоимость позиции)
 * unallocatedCash        = свободные деньги счёта − свободные деньги ботов
 * total                  = botsBalance + unallocatedCash
 * </pre>
 * Развернув, {@code total} равен свободным деньгам счёта плюс рыночная стоимость
 * всех позиций — то есть тому, сколько подключение стоит прямо сейчас.
 *
 * <h3>Как читать null и минус</h3>
 * <ul>
 *   <li>{@code unallocatedCash} отрицателен — ботам роздано больше, чем есть свободных
 *       денег на счёте. Это не ошибка расчёта, а реальная перекладка бюджетов,
 *       и показать её важнее, чем спрятать;</li>
 *   <li>{@code incomplete} — часть ботов в сумму не попала (нет бюджета либо есть
 *       позиция, но нет цены). Тогда {@code botsBalance} заведомо неполон;</li>
 *   <li>{@code currency == null} — валюту определить не удалось, денежные поля пусты.</li>
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
        String currency
) {

    public static ConnectionValuationDto empty() {
        return new ConnectionValuationDto(0, 0, null, null, null, null, null, null, false, null);
    }
}
