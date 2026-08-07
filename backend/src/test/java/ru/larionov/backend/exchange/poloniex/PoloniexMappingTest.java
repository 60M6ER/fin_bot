package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.SymbolTradeLimit;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Отображение символов и лимитов Poloniex.
 *
 * Скучные на вид проверки, но именно здесь живут числа, которыми считается каждая
 * заявка: лишний знак в количестве — отказ биржи, потерянный знак — заявка не того
 * размера.
 */
class PoloniexMappingTest {

    private static Market btcUsdt() {
        SymbolTradeLimit limit = new SymbolTradeLimit();
        limit.setSymbol("BTC_USDT");
        limit.setPriceScale(2);
        limit.setQuantityScale(6);
        limit.setAmountScale(2);
        limit.setMinQuantity("0.000001");
        limit.setMinAmount("1");

        Market market = new Market();
        market.setSymbol("BTC_USDT");
        market.setBaseCurrencyName("BTC");
        market.setQuoteCurrencyName("USDT");
        market.setDisplayName("BTC/USDT");
        market.setState("NORMAL");
        market.setSymbolTradeLimit(limit);
        return market;
    }

    /** uid обязан быть с префиксом биржи: ключ справочника — пара (биржа, uid). */
    @Test
    void uidCarriesTheExchangePrefix() {
        assertThat(PoloniexSymbols.uidOf("BTC_USDT")).isEqualTo("POLONIEX:BTC_USDT");
    }

    @Test
    void symbolIsRecoveredFromUidAndFromBareSymbol() {
        assertThat(PoloniexSymbols.symbolOf(new InstrumentId("POLONIEX:BTC_USDT", null)))
                .isEqualTo("BTC_USDT");
        assertThat(PoloniexSymbols.symbolOf(new InstrumentId("btc_usdt", null)))
                .isEqualTo("BTC_USDT");
    }

    @Test
    void emptyInstrumentIsRejectedRatherThanSilentlyTraded() {
        assertThatThrownBy(() -> PoloniexSymbols.symbolOf(new InstrumentId(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Масштабы превращаются в шаги: quantityScale=6 — это 0.000001 монеты. */
    @Test
    void scalesBecomeSteps() {
        PoloniexSymbols.Limits limits = PoloniexSymbols.Limits.of(btcUsdt());

        assertThat(limits.priceStep()).isEqualByComparingTo("0.01");
        assertThat(limits.quantityStep()).isEqualByComparingTo("0.000001");
        assertThat(limits.minQuantity()).isEqualByComparingTo("0.000001");
        assertThat(limits.minAmount()).isEqualByComparingTo("1");
        assertThat(limits.base()).isEqualTo("BTC");
        assertThat(limits.quote()).isEqualTo("USDT");
    }

    /**
     * Количество округляется ВНИЗ, цена — обычным образом.
     *
     * Разница не косметическая: округлённое вверх количество означало бы заявку
     * чуть больше той, что обеспечена бюджетом и разрешена риск-контролем.
     */
    @Test
    void quantityIsRoundedDownAndPriceIsFormattedToScale() {
        PoloniexSymbols.Limits limits = PoloniexSymbols.Limits.of(btcUsdt());

        assertThat(limits.formatQuantity(new BigDecimal("0.00012399999")))
                .as("лишние знаки отбрасываются вниз, а не округляются вверх")
                .isEqualTo("0.000123");
        assertThat(limits.formatPrice(new BigDecimal("64123.456"))).isEqualTo("64123.46");
    }

    /** Слишком мелкое количество превращается в ноль, а не в минимальный шаг. */
    @Test
    void quantityBelowTheStepBecomesZero() {
        PoloniexSymbols.Limits limits = PoloniexSymbols.Limits.of(btcUsdt());

        assertThat(new BigDecimal(limits.formatQuantity(new BigDecimal("0.0000001"))))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
