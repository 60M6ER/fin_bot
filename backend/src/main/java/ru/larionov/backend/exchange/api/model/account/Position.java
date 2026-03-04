package ru.larionov.backend.exchange.api.model.account;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;

public record Position(
        InstrumentId instrumentId,
        BigDecimal quantity,
        BigDecimal averagePrice,     // опционально, может быть null если биржа не отдаёт
        BigDecimal currentPrice,     // опционально
        BigDecimal unrealizedPnl     // опционально
) {
}
