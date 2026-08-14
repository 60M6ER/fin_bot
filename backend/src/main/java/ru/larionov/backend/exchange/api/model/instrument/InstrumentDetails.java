package ru.larionov.backend.exchange.api.model.instrument;

import java.math.BigDecimal;

/**
 * Подробности инструмента, спрошенные у биржи в момент, когда они нужны.
 *
 * В отличие от {@link InstrumentSnapshot}, который лежит в медленно обновляемом
 * справочнике, здесь живёт то, что обязано быть свежим: лотность, шаг цены и
 * маржинальные требования. Ставки риска брокер меняет по мере надобности, и
 * протухшая ставка в справочнике была бы хуже отсутствующей — она выглядит как знание.
 *
 * @param shortEnabled разрешён ли брокером шорт по этой бумаге. Шортить то, что
 *                     в его список не входит, нельзя ни при каких настройках бота
 * @param dshort       ставка риска для короткой позиции: доля от её стоимости,
 *                     которую брокер требует держать обеспечением
 * @param dshortMin    минимальная ставка риска для короткой позиции
 * @param dlong        то же для длинной позиции
 * @param dlongMin     минимальная ставка риска для длинной позиции
 */
public record InstrumentDetails(
        InstrumentBrief brief,
        int lot,
        BigDecimal minPriceIncrement,
        boolean buyAvailable,
        boolean sellAvailable,
        boolean apiTradeAvailable,
        boolean shortEnabled,
        BigDecimal dshort,
        BigDecimal dshortMin,
        BigDecimal dlong,
        BigDecimal dlongMin
) {

    /** Подробности без маржинальных сведений: площадка, которая их не сообщает. */
    public InstrumentDetails(InstrumentBrief brief, int lot, BigDecimal minPriceIncrement,
                             boolean buyAvailable, boolean sellAvailable, boolean apiTradeAvailable) {
        this(brief, lot, minPriceIncrement, buyAvailable, sellAvailable, apiTradeAvailable,
                false, null, null, null, null);
    }
}
