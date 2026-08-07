package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.event.spot.OrderEvent;
import com.poloniex.api.client.spot.model.event.spot.PoloEvent;
import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.SymbolTradeLimit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Комиссия Poloniex в БАЗОВОЙ валюте — отдельная ловушка, которой нет у T-Invest.
 *
 * При покупке BTC_USDT комиссия удерживается биткойном: в заявке 0.001 BTC,
 * а на баланс придёт 0.001 минус комиссия. Если записать в журнал заявленное
 * количество, произойдёт цепочка: позиция журнала больше фактической → встречную
 * продажу отбивает биржа → сверка бесконечно сообщает о расхождении → бот встаёт.
 *
 * Тест фиксирует, что в журнал попадает НЕТТО.
 */
class PoloniexBaseCurrencyFeeTest {

    private PoloniexSymbols symbols;
    private PoloniexOrdersStreamService stream;

    @BeforeEach
    void setUp() {
        PoloniexRest rest = mock(PoloniexRest.class);
        symbols = mock(PoloniexSymbols.class);
        when(symbols.limits(anyString())).thenReturn(PoloniexSymbols.Limits.of(btcUsdt()));

        // Вебсокет не поднимаем: проверяем чистое отображение события в состояние.
        stream = new PoloniexOrdersStreamService("wss://example.invalid", "k", "s", symbols);
    }

    private static Market btcUsdt() {
        SymbolTradeLimit limit = new SymbolTradeLimit();
        limit.setPriceScale(2);
        limit.setQuantityScale(6);
        limit.setMinQuantity("0.000001");
        limit.setMinAmount("1");

        Market market = new Market();
        market.setSymbol("BTC_USDT");
        market.setBaseCurrencyName("BTC");
        market.setQuoteCurrencyName("USDT");
        market.setState("NORMAL");
        market.setSymbolTradeLimit(limit);
        return market;
    }

    private static OrderEvent buyEvent(String filled, String fee, String feeCurrency) {
        OrderEvent event = new OrderEvent();
        event.setSymbol("BTC_USDT");
        event.setSide("BUY");
        event.setState("FILLED");
        event.setOrderId("exch-1");
        event.setClientOrderId("our-1");
        event.setQuantity("0.001000");
        event.setFilledQuantity(filled);
        event.setPrice("60000");
        event.setTradePrice("60000");
        event.setTradeFee(fee);
        event.setFeeCurrency(feeCurrency);
        return event;
    }

    /** Достаём приватное отображение: поднимать вебсокет ради него незачем. */
    @SuppressWarnings("unchecked")
    private OrderState map(OrderEvent event) throws Exception {
        Method method = PoloniexOrdersStreamService.class
                .getDeclaredMethod("toState", OrderEvent.class, AccountId.class);
        method.setAccessible(true);
        return (OrderState) method.invoke(stream, event, new AccountId("acc-1"));
    }

    /**
     * Главный случай: комиссия удержана биткойном. В журнал обязано попасть то,
     * что реально зачислено, — иначе бот попытается продать монеты, которых нет.
     */
    @Test
    void buyFeeInBaseCurrencyReducesTheRecordedQuantity() throws Exception {
        OrderState state = map(buyEvent("0.001000", "0.0000015", "BTC"));

        assertThat(state.executedQuantity())
                .as("зачислено меньше заявленного ровно на комиссию, вниз до шага")
                .isEqualByComparingTo("0.000998");
        assertThat(state.requestedQuantity()).isEqualByComparingTo("0.001000");
    }

    /** Комиссия пересчитывается в деньги котировки — книга обязана остаться однородной. */
    @Test
    void baseCurrencyFeeIsConvertedToQuoteMoneyForTheLedger() throws Exception {
        OrderState state = map(buyEvent("0.001000", "0.0000015", "BTC"));

        assertThat(state.fee()).isNotNull();
        // 0.0000015 BTC × 60000 = 0.09 USDT
        assertThat(state.fee().amount()).isEqualByComparingTo("0.09");
        assertThat(state.fee().currency())
                .as("исходная валюта комиссии сохраняется: видно, что число получено пересчётом")
                .isEqualTo("BTC");
        assertThat(state.fee().actual()).isTrue();
    }

    /** Комиссия деньгами котировки количество не трогает. */
    @Test
    void quoteCurrencyFeeLeavesTheQuantityIntact() throws Exception {
        OrderState state = map(buyEvent("0.001000", "0.05", "USDT"));

        assertThat(state.executedQuantity()).isEqualByComparingTo("0.001000");
        assertThat(state.fee().amount()).isEqualByComparingTo("0.05");
        assertThat(state.fee().currency()).isEqualTo("USDT");
    }

    /** Чужая заявка без нашего идентификатора — не наше дело, а не ошибка. */
    @Test
    void eventWithoutOurClientOrderIdIsIgnored() throws Exception {
        OrderEvent foreign = buyEvent("0.001000", "0.0000015", "BTC");
        foreign.setClientOrderId(null);

        assertThat(map(foreign)).isNull();
    }

    @Test
    void statesAreMappedToOurVocabulary() throws Exception {
        OrderEvent event = buyEvent("0.000500", null, null);

        event.setState("PARTIALLY_FILLED");
        assertThat(map(event).status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);

        // Частично исполнена и снята — заявка мертва, ждать от неё нечего.
        event.setState("PARTIALLY_CANCELED");
        assertThat(map(event).status()).isEqualTo(OrderStatus.CANCELLED);

        event.setState("SOMETHING_NEW");
        assertThat(map(event).status())
                .as("незнакомое состояние не выдаём за исполнение")
                .isEqualTo(OrderStatus.UNKNOWN);
    }

    @Test
    void sideAndInstrumentSurviveTheMapping() throws Exception {
        OrderState state = map(buyEvent("0.001000", null, null));

        assertThat(state.side()).isEqualTo(OrderSide.BUY);
        assertThat(state.instrumentId().primary()).isEqualTo("POLONIEX:BTC_USDT");
        assertThat(state.clientOrderId().value()).isEqualTo("our-1");
    }
}
