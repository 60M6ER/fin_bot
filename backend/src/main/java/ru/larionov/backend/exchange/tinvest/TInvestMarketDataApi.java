package ru.larionov.backend.exchange.tinvest;

import com.google.protobuf.Timestamp;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.CandlesQuery;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T-Invest implementation of MarketDataApi.
 */
public class TInvestMarketDataApi implements MarketDataApi {

    private final TInvestExchangeClient client;
    private final Map<String, String> quoteCurrencyByUid = new ConcurrentHashMap<>();

    public TInvestMarketDataApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public LastPrice getLastPrice(InstrumentId instrumentId) {
        Map<InstrumentId, LastPrice> map = getLastPrices(Collections.singleton(instrumentId));
        return map.get(instrumentId);
    }

    @Override
    public Map<InstrumentId, LastPrice> getLastPrices(Collection<InstrumentId> instrumentIds) {
        Objects.requireNonNull(instrumentIds, "instrumentIds");

        // Preserve caller's InstrumentId objects where possible (so Map keys match input keys)
        Map<String, InstrumentId> byUid = new HashMap<>();
        for (InstrumentId id : instrumentIds) {
            if (id == null) continue;
            if (id.uid() != null && !id.uid().isBlank()) {
                byUid.put(id.uid(), id);
            }
        }

        if (byUid.isEmpty()) {
            return Collections.emptyMap();
        }

        GetLastPricesRequest request = GetLastPricesRequest.newBuilder()
                .addAllInstrumentId(byUid.keySet())
                .build();

        GetLastPricesResponse response = client.marketDataStub()
                .callSyncMethod(
                        MarketDataServiceGrpc.getGetLastPricesMethod(),
                        stub -> stub.getLastPrices(request)
                );

        Map<InstrumentId, LastPrice> result = new HashMap<>();
        for (ru.tinkoff.piapi.contract.v1.LastPrice lp : response.getLastPricesList()) {
            String uid = lp.getInstrumentUid();
            InstrumentId id = byUid.getOrDefault(uid, new InstrumentId(uid, null));
            result.put(id, new LastPrice(
                    id,
                    toPrice(lp.getPrice(), resolveQuoteCurrency(id)),
                    toInstant(lp.getTime())
            ));
        }

        return result;
    }

    @Override
    public OrderBook getOrderBook(InstrumentId instrumentId, int depth) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (instrumentId.uid() == null || instrumentId.uid().isBlank()) {
            throw new IllegalArgumentException("InstrumentId.uid is required for T-Invest market data calls");
        }
        if (depth <= 0) {
            throw new IllegalArgumentException("depth must be > 0");
        }

        GetOrderBookRequest request = GetOrderBookRequest.newBuilder()
                .setInstrumentId(instrumentId.uid())
                .setDepth(depth)
                .build();

        GetOrderBookResponse response = client.marketDataStub()
                .callSyncMethod(
                        MarketDataServiceGrpc.getGetOrderBookMethod(),
                        stub -> stub.getOrderBook(request)
                );

        return new OrderBook(
                instrumentId,
                depth,
                mapLevels(instrumentId, response.getBidsList()),
                mapLevels(instrumentId, response.getAsksList()),
                toPrice(response.getLimitUp(), resolveQuoteCurrency(instrumentId)),
                toPrice(response.getLimitDown(), resolveQuoteCurrency(instrumentId)),
                // В ответе orderbook время может отсутствовать/не быть доступным в SDK. Для наших расчётов важнее момент получения.
                Instant.now()
        );
    }

    @Override
    public List<Candle> getCandles(InstrumentId instrumentId, CandlesQuery query) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(query, "query");

        if (instrumentId.uid() == null || instrumentId.uid().isBlank()) {
            throw new IllegalArgumentException("InstrumentId.uid is required for T-Invest market data calls");
        }

        GetCandlesRequest request = GetCandlesRequest.newBuilder()
                .setInstrumentId(instrumentId.uid())
                .setFrom(toTimestamp(query.from()))
                .setTo(toTimestamp(query.to()))
                .setInterval(mapInterval(query.interval()))
                .build();

        GetCandlesResponse response = client.marketDataStub()
                .callSyncMethod(
                        MarketDataServiceGrpc.getGetCandlesMethod(),
                        stub -> stub.getCandles(request)
                );

        List<Candle> candles = new ArrayList<>(response.getCandlesCount());
        for (HistoricCandle c : response.getCandlesList()) {
            Instant startTs = toInstant(c.getTime());
            Instant endTs = startTs == null ? null : startTs.plus(intervalDuration(query.interval()));

            candles.add(new Candle(
                    instrumentId,
                    toPrice(c.getOpen(), resolveQuoteCurrency(instrumentId)),
                    toPrice(c.getHigh(), resolveQuoteCurrency(instrumentId)),
                    toPrice(c.getLow(), resolveQuoteCurrency(instrumentId)),
                    toPrice(c.getClose(), resolveQuoteCurrency(instrumentId)),
                    BigDecimal.valueOf(c.getVolume()),
                    startTs,
                    endTs
            ));
        }
        return candles;
    }

    private List<OrderBookLevel> mapLevels(InstrumentId instrumentId, List<Order> orders) {
        List<OrderBookLevel> result = new ArrayList<>(orders.size());
        for (Order o : orders) {
            result.add(new OrderBookLevel(
                    toPrice(o.getPrice(), resolveQuoteCurrency(instrumentId)),
                    BigDecimal.valueOf(o.getQuantity())
            ));
        }
        return result;
    }

    private String resolveQuoteCurrency(InstrumentId instrumentId) {
        if (instrumentId == null) {
            return null;
        }
        String uid = instrumentId.uid();
        if (uid == null || uid.isBlank()) {
            return null;
        }

        return quoteCurrencyByUid.computeIfAbsent(uid, __ -> {
            // NOTE: This is a lazy fetch. For performance, we can later pre-warm the cache
            // by calling instruments().list(...) on startup.
            try {
                return client.instruments().get(instrumentId).brief().quoteCurrency();
            } catch (Exception e) {
                // If we fail to resolve currency, fall back to null to not break market data.
                return null;
            }
        });
    }

    private static BigDecimal quotationToBigDecimal(Quotation q) {
        if (q == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        return units.add(nano);
    }

    private static Price toPrice(Quotation q, String quoteCurrency) {
        BigDecimal v = quotationToBigDecimal(q);
        return new Price(v, quoteCurrency);
    }

    private static Instant toInstant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static CandleInterval mapInterval(ru.larionov.backend.exchange.api.enums.CandleInterval interval) {
        return switch (interval) {
            case M1 -> CandleInterval.CANDLE_INTERVAL_1_MIN;
            case M5 -> CandleInterval.CANDLE_INTERVAL_5_MIN;
            case M15 -> CandleInterval.CANDLE_INTERVAL_15_MIN;
            case H1 -> CandleInterval.CANDLE_INTERVAL_HOUR;
            case D1 -> CandleInterval.CANDLE_INTERVAL_DAY;
        };
    }

    private static java.time.Duration intervalDuration(ru.larionov.backend.exchange.api.enums.CandleInterval interval) {
        return switch (interval) {
            case M1 -> java.time.Duration.ofMinutes(1);
            case M5 -> java.time.Duration.ofMinutes(5);
            case M15 -> java.time.Duration.ofMinutes(15);
            case H1 -> java.time.Duration.ofHours(1);
            case D1 -> java.time.Duration.ofDays(1);
        };
    }
}