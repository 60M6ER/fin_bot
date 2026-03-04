package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.enums.ExchangeType;

import java.math.BigDecimal;

public record ExchangeMeta(
        ExchangeType type,
        boolean supportsTradingCalendar,
        boolean supportsMarketDataStream,
        boolean supportsOrderEventsStream,
        boolean supportsFutures,
        boolean supportsSandbox
) {
}
