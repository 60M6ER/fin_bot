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
 *
 * @param shortEnabled разрешён ли брокером шорт по этой бумаге. Справочно: на торговлю
 *                     не влияет — бот всё равно спрашивает это у биржи на старте, потому
 *                     что список шортируемых бумаг брокер меняет. Но человеку, который
 *                     заводит шортовую сетку, лучше увидеть отказ в форме, а не в журнале
 * @param shortInitialMarginRate ставка риска короткой позиции (dshort): доля её стоимости,
 *                     которую брокер держит обеспечением. Null — брокер не сообщил:
 *                     в справочнике её нет вовсе, и приходит она только живым запросом
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
        Instant expiresAt,
        boolean shortEnabled,
        BigDecimal shortInitialMarginRate
) {

    /** Инструмент без маржинальных сведений: площадка, которая их не сообщает. */
    public InstrumentDto(String uid, String figi, ExchangeType exchange, InstrumentKind kind,
                         MarketSegment segment, String ticker, String name, String classCode,
                         String venue, String currency, int lot, BigDecimal minPriceIncrement,
                         boolean apiTradeAvailable, boolean active, Instant expiresAt) {
        this(uid, figi, exchange, kind, segment, ticker, name, classCode, venue, currency,
                lot, minPriceIncrement, apiTradeAvailable, active, expiresAt, false, null);
    }

    /** Копия с маржинальными ставками, добытыми живым запросом к брокеру. */
    public InstrumentDto withShortMargin(boolean enabled, BigDecimal initialMarginRate) {
        return new InstrumentDto(uid, figi, exchange, kind, segment, ticker, name, classCode,
                venue, currency, lot, minPriceIncrement, apiTradeAvailable, active, expiresAt,
                enabled, initialMarginRate);
    }
}
