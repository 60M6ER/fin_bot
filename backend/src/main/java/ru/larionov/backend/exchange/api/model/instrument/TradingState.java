package ru.larionov.backend.exchange.api.model.instrument;

import java.time.Instant;

public record TradingState(
        boolean tradableNow,
        Instant nextCloseUtc,   // ближайший конец торгового интервала
        Instant nextOpenUtc     // ближайшее начало торгового интервала
) {
}
