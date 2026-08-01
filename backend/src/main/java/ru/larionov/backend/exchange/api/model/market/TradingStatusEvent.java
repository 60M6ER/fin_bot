package ru.larionov.backend.exchange.api.model.market;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.time.Instant;

/**
 * Событие смены торгового статуса инструмента.
 *
 * Для сетки это главный триггер жизненного цикла: у T-Invest нет GTC, все лимитные
 * ордера умирают в конце сессии, поэтому переход в торгуемое состояние означает
 * «сверься с биржей и расставь сетку заново».
 *
 * @param limitOrdersAvailable можно ли прямо сейчас выставлять лимитные ордера.
 *                             Именно этот признак, а не tradable: в аукционе открытия
 *                             торги идут, но лимитные заявки могут не приниматься.
 */
public record TradingStatusEvent(
        InstrumentId instrumentId,
        boolean tradable,
        boolean limitOrdersAvailable,
        String rawStatus,
        Instant ts
) {
}
