package ru.larionov.backend.exchange.api.model;

import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;

public record FeeInfo(
        BigDecimal makerBuyRate,
        BigDecimal makerSellRate,
        BigDecimal takerBuyRate,
        BigDecimal takerSellRate
) {
    public FeeInfo(BigDecimal makerRate, BigDecimal takerRate) {
        this(makerRate, makerRate, takerRate, takerRate);
    }

    public FeeInfo {
        makerBuyRate = nonNegative(makerBuyRate);
        makerSellRate = nonNegative(makerSellRate);
        takerBuyRate = nonNegative(takerBuyRate);
        takerSellRate = nonNegative(takerSellRate);
    }

    /**
     * Обратная совместимость для мест, которым нужна одна консервативная maker-ставка.
     * Для проверки сетки лучше использовать makerRoundTripRate().
     */
    public BigDecimal makerRate() {
        return makerBuyRate.max(makerSellRate);
    }

    public BigDecimal takerRate() {
        return takerBuyRate.max(takerSellRate);
    }

    public BigDecimal makerRoundTripRate() {
        return makerBuyRate.add(makerSellRate);
    }

    public BigDecimal takerRoundTripRate() {
        return takerBuyRate.add(takerSellRate);
    }

    public BigDecimal makerRateFor(OrderSide side) {
        return side == OrderSide.SELL ? makerSellRate : makerBuyRate;
    }

    public BigDecimal takerRateFor(OrderSide side) {
        return side == OrderSide.SELL ? takerSellRate : takerBuyRate;
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value;
    }
}
