package ru.larionov.backend.exchange.api.model.instrument;

import java.time.LocalDate;
import java.util.List;

public record TradingDaySchedule(
        LocalDate date,      // дата дня по UTC или по venue-зоне (лучше LocalDate + отдельная zone)
        boolean isTradingDay,
        List<TradingInterval> intervals
) {
}
