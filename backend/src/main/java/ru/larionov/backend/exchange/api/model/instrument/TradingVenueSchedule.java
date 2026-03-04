package ru.larionov.backend.exchange.api.model.instrument;

import java.util.List;

public record TradingVenueSchedule(
        String venue,
        List<TradingDaySchedule> days
) {
}
