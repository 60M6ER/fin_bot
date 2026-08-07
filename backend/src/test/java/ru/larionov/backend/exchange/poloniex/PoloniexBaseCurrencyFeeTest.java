package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.Order;
import com.poloniex.api.client.spot.model.response.spot.SymbolTradeLimit;
import com.poloniex.api.client.spot.model.response.spot.Trade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Комиссия Poloniex в БАЗОВОЙ валюте — отдельная ловушка, которой нет у T-Invest.
 *
 * При покупке DOGE_USDT комиссия удерживается монетой: в заявке 140.826 DOGE,
 * а на баланс придёт 140.826 минус комиссия. Если записать в журнал заявленное
 * количество, произойдёт цепочка: позиция журнала больше фактической → встречную
 * продажу отбивает биржа с кодом 21721 «available insufficient» → сверка
 * бесконечно сообщает о расхождении → бот встаёт.
 *
 * Ровно это и случилось на боевом прогоне 07.08.2026: расхождение −0.281652 DOGE,
 * то есть в точности 0.2% от 140.826 — вся комиссия целиком.
 *
 * Числа в тесте настоящие, из того самого инцидента.
 */
class PoloniexBaseCurrencyFeeTest {

    private PoloniexRest rest;
    private PoloniexRestApi api;
    private PoloniexOrdersApi orders;

    @BeforeEach
    void setUp() {
        rest = mock(PoloniexRest.class);
        api = mock(PoloniexRestApi.class);
        when(rest.api()).thenReturn(api);

        PoloniexSymbols symbols = mock(PoloniexSymbols.class);
        when(symbols.limits(anyString())).thenReturn(PoloniexSymbols.Limits.of(dogeUsdt()));

        orders = new PoloniexOrdersApi(rest, symbols);
    }

    /**
     * Шаг количества у DOGE_USDT — 0.001, то есть ГРУБЕЕ комиссии с шестью знаками.
     * Именно поэтому нетто нельзя округлять по этому шагу: на балансе лежит точное
     * число, а сверка не прощает расхождения даже в последнем знаке.
     */
    private static Market dogeUsdt() {
        SymbolTradeLimit limit = new SymbolTradeLimit();
        limit.setSymbol("DOGE_USDT");
        limit.setPriceScale(6);
        limit.setQuantityScale(3);
        limit.setMinQuantity("0.001");
        limit.setMinAmount("1");

        Market market = new Market();
        market.setSymbol("DOGE_USDT");
        market.setBaseCurrencyName("DOGE");
        market.setQuoteCurrencyName("USDT");
        market.setState("NORMAL");
        market.setSymbolTradeLimit(limit);
        return market;
    }

    private static Order filledBuy() {
        Order order = new Order();
        order.setId("exch-1");
        order.setClientOrderId("our-1");
        order.setSymbol("DOGE_USDT");
        order.setSide("BUY");
        order.setState("FILLED");
        order.setQuantity(new BigDecimal("140.826"));
        order.setFilledQuantity(new BigDecimal("140.826"));
        order.setPrice(new BigDecimal("0.070027"));
        order.setAvgPrice(new BigDecimal("0.070013"));
        // Сумма СДЕЛКИ, из которой комиссия не вычтена: ровно количество × цена.
        // Из неё удержание не выводится — на этом и споткнулся прежний расчёт.
        order.setFilledAmount(new BigDecimal("140.826").multiply(new BigDecimal("0.070013")));
        return order;
    }

    private static Trade trade(String quantity, String fee, String feeCurrency) {
        Trade trade = new Trade();
        trade.setOrderId("exch-1");
        trade.setSymbol("DOGE_USDT");
        trade.setSide("BUY");
        trade.setQuantity(quantity);
        trade.setPrice("0.070013");
        trade.setFeeAmount(fee);
        trade.setFeeCurrency(feeCurrency);
        return trade;
    }

    @SuppressWarnings("unchecked")
    private void exchangeReturns(Order order, List<Trade> trades) {
        Call<Order> orderCall = mock(Call.class);
        when(api.orderByClientOrderId(anyString())).thenReturn(orderCall);
        when(rest.callAllowingNotFound(anyString(), eq(orderCall))).thenReturn(Optional.of(order));

        Call<List<Trade>> tradesCall = mock(Call.class);
        when(api.tradesByOrder(anyString())).thenReturn(tradesCall);
        if (trades == null) {
            when(rest.call(anyString(), eq(tradesCall)))
                    .thenThrow(new PoloniexRest.PoloniexApiException(500, "сделки", "boom"));
        } else {
            when(rest.call(anyString(), eq(tradesCall))).thenReturn(trades);
        }
    }

    private OrderState fetch() {
        return orders.getByClientOrderId(new AccountId("acc-1"), new ClientOrderId("our-1")).orElseThrow();
    }

    /**
     * Главный случай инцидента: комиссия удержана монетой. В журнал обязано попасть
     * то, что реально зачислено, — иначе бот попытается продать монеты, которых нет.
     */
    @Test
    void buyFeeInBaseCurrencyReducesTheRecordedQuantity() {
        exchangeReturns(filledBuy(), List.of(trade("140.826", "0.281652", "DOGE")));

        assertThat(fetch().executedQuantity())
                .as("зачислено ровно на комиссию меньше — 140.826 − 0.281652")
                .isEqualByComparingTo("140.544348");
    }

    /**
     * Нетто НЕ округляется до шага количества. Шаг 0.001 — ограничение на заявку,
     * а не на баланс: округлив, мы потеряли бы 0.000348 DOGE, и сверка встала бы
     * на них так же намертво, как раньше вставала на целой комиссии.
     */
    @Test
    void netQuantityKeepsFullPrecisionAndIsNotRoundedToTheOrderStep() {
        exchangeReturns(filledBuy(), List.of(trade("140.826", "0.281652", "DOGE")));

        assertThat(fetch().executedQuantity().stripTrailingZeros().toPlainString())
                .isEqualTo("140.544348");
    }

    /** Комиссия пересчитывается в деньги котировки — книга обязана остаться однородной. */
    @Test
    void baseCurrencyFeeIsConvertedToQuoteMoneyForTheLedger() {
        exchangeReturns(filledBuy(), List.of(trade("140.826", "0.281652", "DOGE")));
        OrderState state = fetch();

        assertThat(state.fee()).isNotNull();
        // 0.281652 DOGE × 0.070013 = 0.019719... USDT
        assertThat(state.fee().amount())
                .isEqualByComparingTo(new BigDecimal("0.281652").multiply(new BigDecimal("0.070013")));
        assertThat(state.fee().currency())
                .as("исходная валюта комиссии сохраняется: видно, что число получено пересчётом")
                .isEqualTo("DOGE");
        assertThat(state.fee().actual()).isTrue();
    }

    /** Заявка, исполненная НЕСКОЛЬКИМИ сделками: удержания складываются все. */
    @Test
    void feesOfEveryTradeAreSummed() {
        Order order = filledBuy();
        exchangeReturns(order, List.of(
                trade("40.826", "0.081652", "DOGE"),
                trade("100.000", "0.200000", "DOGE")));

        assertThat(fetch().executedQuantity())
                .as("140.826 − (0.081652 + 0.200000)")
                .isEqualByComparingTo("140.544348");
    }

    /** Комиссия деньгами котировки количество не трогает. */
    @Test
    void quoteCurrencyFeeLeavesTheQuantityIntact() {
        exchangeReturns(filledBuy(), List.of(trade("140.826", "0.019719", "USDT")));
        OrderState state = fetch();

        assertThat(state.executedQuantity()).isEqualByComparingTo("140.826");
        assertThat(state.fee().amount()).isEqualByComparingTo("0.019719");
        assertThat(state.fee().currency()).isEqualTo("USDT");
    }

    /**
     * Сделки не пришли — комиссия неизвестна. Отдаём брутто, но НЕ выдаём его за
     * подтверждённый расчёт: пока комиссия не факт, сверка вернётся к этой записи,
     * а гейтвей не примет заниженное количество за окончательное.
     */
    @Test
    void unavailableTradesLeaveTheFeeUnconfirmedRatherThanGuessed() {
        exchangeReturns(filledBuy(), null);
        OrderState state = fetch();

        assertThat(state.executedQuantity()).isEqualByComparingTo("140.826");
        assertThat(state.fee()).as("догадка не выдаётся за факт").isNull();
    }

    /**
     * Список сделок отстаёт и покрывает не весь объём. Принять такое удержание —
     * значит снова занизить комиссию и снова получить позицию больше фактической,
     * только теперь на величину, которую труднее заметить.
     */
    @Test
    void partialTradeListIsRejectedInsteadOfUnderstatingTheFee() {
        exchangeReturns(filledBuy(), List.of(trade("40.826", "0.081652", "DOGE")));
        OrderState state = fetch();

        assertThat(state.executedQuantity()).isEqualByComparingTo("140.826");
        assertThat(state.fee()).isNull();
    }

    /** Пока ничего не исполнено, за сделками ходить незачем. */
    @Test
    void restingOrderCostsNoExtraRequest() {
        Order resting = filledBuy();
        resting.setState("NEW");
        resting.setFilledQuantity(BigDecimal.ZERO);
        resting.setFilledAmount(BigDecimal.ZERO);
        resting.setAvgPrice(BigDecimal.ZERO);
        exchangeReturns(resting, List.of());

        assertThat(fetch().executedQuantity()).isEqualByComparingTo("0");
        org.mockito.Mockito.verify(api, org.mockito.Mockito.never()).tradesByOrder(any());
    }
}
