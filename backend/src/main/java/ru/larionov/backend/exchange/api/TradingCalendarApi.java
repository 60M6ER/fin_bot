package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.instrument.TradingCalendar;
import ru.larionov.backend.exchange.api.model.instrument.TradingCalendarQuery;
import ru.larionov.backend.exchange.api.model.instrument.TradingState;

import java.time.Instant;

public interface TradingCalendarApi {
    TradingCalendar getCalendar(TradingCalendarQuery q);
    TradingState getState(Instant now);
}
