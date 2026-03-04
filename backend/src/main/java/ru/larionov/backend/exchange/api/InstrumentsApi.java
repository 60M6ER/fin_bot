package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentDetails;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentsQuery;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;

import java.util.List;

public interface InstrumentsApi {
    List<InstrumentBrief> list(InstrumentsQuery q);
    InstrumentDetails get(InstrumentId id);
    TradingConstraints getConstraints(InstrumentId id);
}
