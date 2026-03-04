package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.CandlesQuery;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface MarketDataApi {
    // Последняя цена по одному инструменту (для расчёта сетки / текущей оценки)
    LastPrice getLastPrice(InstrumentId instrumentId);

    // Пакетный вариант (чтобы не долбить API в цикле)
    Map<InstrumentId, LastPrice> getLastPrices(Collection<InstrumentId> instrumentIds);

    // Стакан: нужен для best bid/ask, спреда и динамических лимитов
    OrderBook getOrderBook(InstrumentId instrumentId, int depth);

    // Опционально, но очень полезно для авто-подбора параметров сетки
    List<Candle> getCandles(InstrumentId instrumentId, CandlesQuery query);
}