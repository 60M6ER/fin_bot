package ru.larionov.backend.exchange.api.model.market;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.time.Instant;
import java.util.List;

public record OrderBook(
        InstrumentId instrumentId,
        int depth,
        List<OrderBookLevel> bids,
        List<OrderBookLevel> asks,

        // Динамические ценовые планки (если биржа отдаёт). На крипте часто null.
        Price limitUp,
        Price limitDown,

        Instant ts
) {
}
