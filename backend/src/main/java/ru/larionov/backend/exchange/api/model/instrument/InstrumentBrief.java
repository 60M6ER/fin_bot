package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

public record InstrumentBrief(
        InstrumentId id,
        InstrumentKind kind,
        String ticker,
        String name,
        String classCode,
        String quoteCurrency
) {
}
