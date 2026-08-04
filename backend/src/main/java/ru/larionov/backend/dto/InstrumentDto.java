package ru.larionov.backend.dto;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Инструмент, каким его видит UI.
 *
 * lot и minPriceIncrement отдаются вместе с подписью не для красоты: форма сетки считает
 * лесенку и безубыток именно по ним, и без них она округляла бы цены не так, как это
 * сделает бот при постановке заявок.
 */
public record InstrumentDto(
        String uid,
        String figi,
        ExchangeType exchange,
        InstrumentKind kind,
        MarketSegment segment,
        String ticker,
        String name,
        String classCode,
        String venue,
        String currency,
        int lot,
        BigDecimal minPriceIncrement,
        boolean apiTradeAvailable,
        boolean active,
        Instant expiresAt
) {
}
