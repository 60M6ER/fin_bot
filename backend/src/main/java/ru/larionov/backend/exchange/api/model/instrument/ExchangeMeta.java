package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.enums.ExchangeType;

import java.util.List;

public record ExchangeMeta(
        ExchangeType type,
        boolean supportsTradingCalendar,
        boolean supportsMarketDataStream,
        boolean supportsOrderEventsStream,
        boolean supportsFutures,
        boolean supportsSandbox,
        /**
         * Валюты, в которых на этой бирже ДЕРЖАТ ДЕНЬГИ, в порядке убывания
         * приоритета. Всё остальное на счёте — товар, а не деньги.
         *
         * Различение нужно потому, что «сколько денег на подключении» нельзя
         * решить сравнением остатков: на криптобирже 10 HTX это пыль на копейку,
         * а 0.9 USDT — реальные деньги, и выбор «того, чего больше по числу»
         * показывал бы в балансе случайную монету.
         *
         * Пустой список означает «биржа не различает» — тогда остаётся сравнение
         * по величине, как было раньше.
         */
        List<String> cashCurrencies,
        /**
         * Отдаёт ли биржа тарифную ставку по API.
         *
         * Если да, ручная ставка в настройках подключения не используется, и
         * показывать её пользователю нельзя: он решит, что вводит число, которым
         * считается безубыток сетки, а считается оно совсем другим.
         */
        boolean feesFromApi
) {
    public ExchangeMeta {
        cashCurrencies = cashCurrencies == null ? List.of() : List.copyOf(cashCurrencies);
    }
}
