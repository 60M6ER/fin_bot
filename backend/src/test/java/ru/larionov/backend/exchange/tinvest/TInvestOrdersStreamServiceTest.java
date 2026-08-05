package ru.larionov.backend.exchange.tinvest;

import org.junit.jupiter.api.Test;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.OrderStateStreamResponse;
import ru.tinkoff.piapi.contract.v1.OrderTrade;
import ru.tinkoff.piapi.contract.v1.Quotation;

import static org.assertj.core.api.Assertions.assertThat;

class TInvestOrdersStreamServiceTest {

    @Test
    void calculatesPerShareAverageFromTrades() {
        var state = OrderStateStreamResponse.OrderState.newBuilder()
                .setLotSize(10)
                .setExecutedOrderPrice(money(223, 600_000_000))
                .addTrades(trade(22, 350_000_000, 4))
                .addTrades(trade(22, 370_000_000, 6))
                .build();

        assertThat(TInvestOrdersStreamService.averageExecutedPrice(state))
                .isEqualByComparingTo("22.362000000");
    }

    @Test
    void convertsLotAmountWhenStreamHasNoTrades() {
        var state = OrderStateStreamResponse.OrderState.newBuilder()
                .setLotSize(10)
                .setExecutedOrderPrice(money(223, 600_000_000))
                .build();

        assertThat(TInvestOrdersStreamService.averageExecutedPrice(state))
                .isEqualByComparingTo("22.360000000");
    }

    private OrderTrade trade(long units, int nano, long quantity) {
        return OrderTrade.newBuilder()
                .setPrice(Quotation.newBuilder().setUnits(units).setNano(nano))
                .setQuantity(quantity)
                .build();
    }

    private MoneyValue money(long units, int nano) {
        return MoneyValue.newBuilder()
                .setCurrency("rub")
                .setUnits(units)
                .setNano(nano)
                .build();
    }
}
