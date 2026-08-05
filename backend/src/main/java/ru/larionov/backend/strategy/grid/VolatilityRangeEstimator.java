package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.CandlesQuery;
import ru.larionov.backend.exchange.api.model.market.LastPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Выбирает стартовый диапазон сетки по средней истинной волатильности свечей. */
@Slf4j
public final class VolatilityRangeEstimator {

    public record Atr(BigDecimal value, int candlesUsed, int missingCandles) {
    }

    public record Estimate(GridRange range, BigDecimal referencePrice, Atr atr) {
    }

    public Atr compute(List<Candle> candles, int periods) {
        if (periods <= 0) {
            throw new IllegalArgumentException("ATR periods должен быть больше нуля");
        }
        List<Candle> source = candles == null ? List.of() : candles;
        int minimum = Math.max(5, periods / 2);
        if (source.size() < minimum) {
            throw new IllegalStateException(
                    "Недостаточно свечей для ATR: получено %d, нужно минимум %d"
                            .formatted(source.size(), minimum));
        }

        int missing = 0;
        List<Candle> valid = new ArrayList<>();
        for (Candle candle : source) {
            if (value(candle == null ? null : candle.high()) == null
                    || value(candle == null ? null : candle.low()) == null
                    || value(candle == null ? null : candle.close()) == null) {
                missing++;
            } else {
                valid.add(candle);
            }
        }
        if (missing * 5 > source.size()) {
            throw new IllegalStateException(
                    "Слишком много неполных свечей для ATR: %d из %d"
                            .formatted(missing, source.size()));
        }
        if (valid.size() < minimum) {
            throw new IllegalStateException(
                    "Недостаточно полных свечей для ATR: получено %d, нужно минимум %d"
                            .formatted(valid.size(), minimum));
        }

        valid.sort(Comparator.comparing(Candle::startTs,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<BigDecimal> trueRanges = new ArrayList<>(valid.size());
        BigDecimal previousClose = null;
        for (Candle candle : valid) {
            BigDecimal high = value(candle.high());
            BigDecimal low = value(candle.low());
            BigDecimal highLow = high.subtract(low).abs();
            BigDecimal tr = previousClose == null
                    ? highLow
                    : highLow.max(high.subtract(previousClose).abs())
                            .max(low.subtract(previousClose).abs());
            trueRanges.add(tr);
            previousClose = value(candle.close());
        }

        int used = Math.min(periods, trueRanges.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = trueRanges.size() - used; i < trueRanges.size(); i++) {
            sum = sum.add(trueRanges.get(i));
        }
        BigDecimal atr = sum.divide(BigDecimal.valueOf(used), 9, RoundingMode.HALF_UP);
        if (atr.signum() <= 0) {
            throw new IllegalStateException("ATR получился нулевым: автоматический диапазон не определён");
        }
        return new Atr(atr, used, missing);
    }

    public Estimate estimate(MarketDataApi marketData,
                             InstrumentId instrumentId,
                             GridConfig cfg,
                             BigDecimal priceIncrement,
                             Instant now) {
        LastPrice last = marketData.getLastPrice(instrumentId);
        BigDecimal price = last == null || last.price() == null ? null : last.price().value();
        if (price == null || price.signum() <= 0) {
            throw new IllegalStateException("Биржа не вернула текущую цену для автоматического диапазона");
        }

        return estimateAround(marketData, instrumentId, cfg, priceIncrement, now, price,
                GridRange.Origin.ATR_INITIAL);
    }

    public Estimate estimateAround(MarketDataApi marketData,
                                   InstrumentId instrumentId,
                                   GridConfig cfg,
                                   BigDecimal priceIncrement,
                                   Instant now,
                                   BigDecimal referencePrice,
                                   GridRange.Origin origin) {
        if (referencePrice == null || referencePrice.signum() <= 0) {
            throw new IllegalArgumentException("Опорная цена автоматического диапазона должна быть больше нуля");
        }

        Instant to = now == null ? Instant.now() : now;
        Duration window = queryWindow(cfg.atrInterval(), cfg.atrPeriods());
        Instant from = to.minus(window);
        List<Candle> candles = marketData.getCandles(
                instrumentId, new CandlesQuery(from, to, cfg.atrInterval()));
        log.info("ATR candles: instrument={}, interval={}, from={}, to={}, count={}",
                instrumentId.primary(), cfg.atrInterval(), from, to, candles == null ? 0 : candles.size());

        Atr atr = compute(candles, cfg.atrPeriods());
        BigDecimal minWidth = referencePrice.multiply(cfg.minHalfWidthPct());
        BigDecimal maxWidth = referencePrice.multiply(cfg.maxHalfWidthPct());
        BigDecimal halfWidth = atr.value().multiply(cfg.atrMultiplier())
                .max(minWidth)
                .min(maxWidth);
        BigDecimal lower = referencePrice.subtract(halfWidth);
        BigDecimal upper = referencePrice.add(halfWidth);
        BigDecimal increment = priceIncrement == null ? BigDecimal.ZERO : priceIncrement;
        if (lower.signum() <= 0 || (increment.signum() > 0 && lower.compareTo(increment) < 0)) {
            throw new IllegalStateException(
                    "Нижняя граница ATR-сетки %s меньше допустимого шага цены %s"
                            .formatted(lower.toPlainString(), increment.toPlainString()));
        }

        return new Estimate(
                new GridRange(lower, upper, cfg.levels(), origin, to),
                referencePrice,
                atr);
    }

    private static Duration queryWindow(CandleInterval interval, int periods) {
        Duration unit = switch (interval) {
            case M1 -> Duration.ofMinutes(1);
            case M5 -> Duration.ofMinutes(5);
            case M15 -> Duration.ofMinutes(15);
            case H1 -> Duration.ofHours(1);
            case D1 -> Duration.ofDays(1);
        };
        Duration desired = unit.multipliedBy(Math.max(5L, periods) * 3L);
        Duration maximum = switch (interval) {
            case M1 -> Duration.ofDays(1);
            case M5 -> Duration.ofDays(3);
            case M15, H1 -> Duration.ofDays(7);
            case D1 -> Duration.ofDays(180);
        };
        return desired.compareTo(maximum) > 0 ? maximum : desired;
    }

    private static BigDecimal value(ru.larionov.backend.exchange.api.model.market.Price price) {
        return price == null ? null : price.value();
    }
}
