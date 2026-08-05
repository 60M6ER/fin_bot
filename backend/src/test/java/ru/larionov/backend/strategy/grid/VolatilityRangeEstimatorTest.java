package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.Price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VolatilityRangeEstimatorTest {

    private final VolatilityRangeEstimator estimator = new VolatilityRangeEstimator();

    @Test
    void computesArithmeticAverageOfTrueRanges() {
        List<Candle> candles = List.of(
                candle(0, "11", "9", "10"),
                candle(1, "13", "10", "12"),
                candle(2, "12", "8", "9"),
                candle(3, "11", "8", "10"),
                candle(4, "12", "9", "11"));

        var atr = estimator.compute(candles, 3);

        assertThat(atr.value()).isEqualByComparingTo("3.333333333");
        assertThat(atr.candlesUsed()).isEqualTo(3);
    }

    @Test
    void rejectsTooManyCandlesWithoutPrices() {
        List<Candle> candles = new ArrayList<>(List.of(
                candle(0, "11", "9", "10"),
                candle(1, "11", "9", "10"),
                candle(2, "11", "9", "10"),
                candle(3, "11", "9", "10"),
                candle(4, "11", "9", "10"),
                candle(5, "11", "9", "10")));
        candles.set(0, candle(0, null, "9", "10"));
        candles.set(1, candle(1, "11", null, "10"));

        assertThatThrownBy(() -> estimator.compute(candles, 6))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Слишком много неполных свечей");
    }

    @Test
    void rejectsShortHistory() {
        assertThatThrownBy(() -> estimator.compute(List.of(
                candle(0, "11", "9", "10"),
                candle(1, "11", "9", "10"),
                candle(2, "11", "9", "10"),
                candle(3, "11", "9", "10")), 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Недостаточно свечей");
    }

    private static Candle candle(long hour, String high, String low, String close) {
        InstrumentId instrument = new InstrumentId("uid-1", null);
        return new Candle(instrument, price(close), price(high), price(low), price(close),
                BigDecimal.ONE, Instant.parse("2026-01-01T00:00:00Z").plusSeconds(hour * 3600), null);
    }

    private static Price price(String value) {
        return value == null ? new Price(null, "rub") : new Price(new BigDecimal(value), "rub");
    }
}
