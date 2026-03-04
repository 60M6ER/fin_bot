package ru.larionov.backend.exchange.api.model.instrument;

import java.time.Instant;

public record TradingCalendarQuery(
        String venue,        // optional: биржа/календарь (MOEX, SPB, etc)
        Instant fromUtc,
        Instant toUtc
) {
}
