package ru.larionov.backend.exchange.api.model.instrument;

import java.util.List;

public record TradingCalendar(
        List<TradingVenueSchedule> venues
) {
}
