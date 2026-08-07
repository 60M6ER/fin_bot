package ru.larionov.backend.dto;

/**
 * Валюта сводного баланса и источник курса.
 *
 * Оба поля необязательны: null означает «не менять», чтобы экран настроек мог
 * сохранить одно из них, не трогая второе.
 *
 * @param displayCurrency RUB или USD. Влияет ТОЛЬКО на показ: бюджеты ботов
 *                        и риск-лимиты остаются в валюте своего инструмента
 * @param fxSource        CBR или T_INVEST
 */
public record UpdateDisplayCurrencyDto(
        String displayCurrency,
        String fxSource
) {}
