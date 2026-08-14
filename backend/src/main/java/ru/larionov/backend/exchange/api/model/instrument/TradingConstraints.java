package ru.larionov.backend.exchange.api.model.instrument;

import java.math.BigDecimal;

/**
 * Ограничения биржи на заявку по конкретному инструменту.
 *
 * Раньше здесь был один {@code int lot}, потому что T-Invest торгует целыми лотами
 * и этого хватало. Криптобирже этого мало: у неё заявочная единица — сама монета,
 * а дробить её можно до {@code quantityStep} (BTC_USDT — до 0.000001), и есть
 * отдельный минимум по сумме заявки.
 *
 * @param exchangeLotSize  сколько единиц базового актива в одной ЗАЯВОЧНОЙ единице биржи.
 *                         T-Invest: 1/10/100. Poloniex: 1
 * @param quantityStep     минимальный шаг количества. У T-Invest равен лоту —
 *                         меньше лота не купить
 * @param minQuantity      минимальное количество в заявке, null — биржа не задаёт
 * @param minNotional      минимальная СУММА заявки, null — биржа не задаёт.
 *                         У Poloniex это minAmount и он реально отсекает мелкие заявки
 * @param minPriceIncrement шаг цены
 * @param quoteCurrency    валюта котировки: в ней выражены цена, бюджет и весь P/L бота
 * @param shortEnabled     разрешён ли брокером шорт по этой бумаге. Спрашивается у биржи
 *                         в момент старта бота, а не берётся из справочника: список
 *                         шортируемых бумаг брокер меняет, и вчерашнее «да» ничего не значит
 * @param shortInitialMarginRate ставка риска короткой позиции (dshort): доля её стоимости,
 *                         которую брокер требует держать обеспечением. null — брокер
 *                         не сообщил, и открывать шорт по такому инструменту нельзя:
 *                         посчитать требуемое обеспечение не из чего
 * @param shortMinMarginRate минимальная ставка риска короткой позиции (dshortMin)
 */
public record TradingConstraints(
        BigDecimal exchangeLotSize,
        BigDecimal quantityStep,
        BigDecimal minQuantity,
        BigDecimal minNotional,
        BigDecimal minPriceIncrement,
        String quoteCurrency,
        boolean shortEnabled,
        BigDecimal shortInitialMarginRate,
        BigDecimal shortMinMarginRate
) {

    /** Ограничения без маржинальных сведений: площадка, которая их не сообщает. */
    public TradingConstraints(BigDecimal exchangeLotSize, BigDecimal quantityStep,
                              BigDecimal minQuantity, BigDecimal minNotional,
                              BigDecimal minPriceIncrement, String quoteCurrency) {
        this(exchangeLotSize, quantityStep, minQuantity, minNotional, minPriceIncrement,
                quoteCurrency, false, null, null);
    }

    public TradingConstraints {
        if (exchangeLotSize == null || exchangeLotSize.signum() <= 0) {
            exchangeLotSize = BigDecimal.ONE;
        }
        if (quantityStep == null || quantityStep.signum() <= 0) {
            // Без явного шага считаем, что мельче заявочной единицы дробить нельзя.
            quantityStep = exchangeLotSize;
        }
    }

    /** Биржа с целыми лотами: шаг количества равен заявочной единице. */
    public static TradingConstraints wholeLots(int lot, BigDecimal minPriceIncrement, String quoteCurrency) {
        BigDecimal lotSize = BigDecimal.valueOf(Math.max(1, lot));
        return new TradingConstraints(lotSize, lotSize, lotSize, null, minPriceIncrement, quoteCurrency);
    }

    /** То же, но с маржинальными сведениями брокера. */
    public static TradingConstraints wholeLots(int lot, BigDecimal minPriceIncrement, String quoteCurrency,
                                               boolean shortEnabled, BigDecimal shortInitialMarginRate,
                                               BigDecimal shortMinMarginRate) {
        BigDecimal lotSize = BigDecimal.valueOf(Math.max(1, lot));
        return new TradingConstraints(lotSize, lotSize, lotSize, null, minPriceIncrement, quoteCurrency,
                shortEnabled, shortInitialMarginRate, shortMinMarginRate);
    }
}
