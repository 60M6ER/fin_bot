package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.exchange.api.enums.InstrumentKind;

import java.util.Set;

public record InstrumentsQuery(
        Set<InstrumentKind> kinds,     // null/empty = "всё доступное"
        String ticker,                // optional
        String query,                 // optional: name/ticker contains
        boolean onlyTradable          // optional
) {}
