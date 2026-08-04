package ru.larionov.backend.exchange.api.model.instrument;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Полный слепок инструмента от биржи — то, что попадает в наш справочник.
 *
 * Держит {@link InstrumentBrief} композицией, а не копирует его поля: иначе стало бы
 * возможно расхождение «в brief один тикер, в снимке другой».
 *
 * Сознательно НЕ храним маржинальные требования, basicAssetSize, direction, style и прочие
 * execution-time параметры: их и так тянет живьём {@code getConstraints()} при старте бота,
 * а в справочнике, обновляемом раз в несколько часов, они были бы протухшими by design.
 *
 * @param tradingStatus строкой, а не enum брокера: нам нужно только показать её,
 *                      а тащить в общий контракт вендорское перечисление незачем
 * @param venueRaw      сырая строка площадки от брокера — для разбора инцидентов
 * @param basicAsset    базовый актив; только у FUTURE/OPTION, иначе null
 * @param expiresAt     экспирация; только у FUTURE/OPTION, иначе null
 * @param strikePrice   страйк; только у OPTION, иначе null
 */
public record InstrumentSnapshot(
        InstrumentBrief brief,
        int lot,
        BigDecimal minPriceIncrement,
        boolean buyAvailable,
        boolean sellAvailable,
        boolean apiTradeAvailable,
        boolean shortEnabled,
        boolean weekendTrading,
        String tradingStatus,
        String isin,
        String venueRaw,
        String basicAsset,
        Instant expiresAt,
        BigDecimal strikePrice
) {
    public InstrumentDetails toDetails() {
        return new InstrumentDetails(brief, lot, minPriceIncrement,
                buyAvailable, sellAvailable, apiTradeAvailable);
    }
}
