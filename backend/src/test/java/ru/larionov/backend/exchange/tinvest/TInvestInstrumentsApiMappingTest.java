package ru.larionov.backend.exchange.tinvest;

import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentSnapshot;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Маппинг протобуфа брокера в наш справочник.
 *
 * Проверять его стоит именно так, по одному собранному вручную сообщению на тип: наборы
 * полей у шести типов T-Invest разные, и молча потерянное поле (например figi у опциона)
 * иначе всплыло бы уже данными в БД.
 */
class TInvestInstrumentsApiMappingTest {

    private static Quotation quotation(long units, int nano) {
        return Quotation.newBuilder().setUnits(units).setNano(nano).build();
    }

    @Test
    void shareMapsToSpotSegmentAndKeepsBothIdentifiers() {
        Share share = Share.newBuilder()
                .setUid("e6123145-9665-43e0-8413-cd61b8aa9b13")
                .setFigi("BBG004730N88")
                .setIsin("RU0009029540")
                .setTicker("SBER")
                .setName("Сбер Банк")
                .setClassCode("TQBR")
                .setCurrency("rub")
                .setExchange("MOEX_EVENING_WEEKEND")
                .setRealExchange(RealExchange.REAL_EXCHANGE_MOEX)
                .setLot(10)
                .setMinPriceIncrement(quotation(0, 10_000_000)) // 0.01
                .setBuyAvailableFlag(true)
                .setSellAvailableFlag(true)
                .setApiTradeAvailableFlag(true)
                .setShortEnabledFlag(true)
                .setWeekendFlag(true)
                .setTradingStatus(SecurityTradingStatus.SECURITY_TRADING_STATUS_NORMAL_TRADING)
                .build();

        InstrumentSnapshot s = TInvestInstrumentsApi.mapShare(share);

        assertThat(s.brief().kind()).isEqualTo(InstrumentKind.SHARE);
        assertThat(s.brief().segment()).isEqualTo(MarketSegment.SPOT);
        assertThat(s.brief().id().uid()).isEqualTo("e6123145-9665-43e0-8413-cd61b8aa9b13");
        assertThat(s.brief().id().figi()).isEqualTo("BBG004730N88");
        // Свободную строку площадки в подписи показывать нельзя — нормализуем.
        assertThat(s.brief().venue()).isEqualTo("MOEX");
        assertThat(s.venueRaw()).isEqualTo("MOEX_EVENING_WEEKEND");
        // Брокер отдаёт валюту строчными, рядом в подписи стоят MOEX и TQBR.
        assertThat(s.brief().quoteCurrency()).isEqualTo("RUB");
        assertThat(s.lot()).isEqualTo(10);
        assertThat(s.minPriceIncrement()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(s.tradingStatus()).isEqualTo("SECURITY_TRADING_STATUS_NORMAL_TRADING");
    }

    @Test
    void futureCarriesBasicAssetAndExpiration() {
        Future future = Future.newBuilder()
                .setUid("uid-si")
                .setFigi("FUTSI0000000")
                .setTicker("SiZ6")
                .setName("Si-12.26 Курс Доллар – Рубль")
                .setClassCode("SPBFUT")
                .setCurrency("rub")
                .setExchange("FORTS_EVENING")
                .setRealExchange(RealExchange.REAL_EXCHANGE_RTS)
                .setLot(1)
                .setMinPriceIncrement(quotation(1, 0))
                .setBasicAsset("USDRUB")
                .setExpirationDate(Timestamp.newBuilder().setSeconds(1_797_120_000L).build())
                .build();

        InstrumentSnapshot s = TInvestInstrumentsApi.mapFuture(future);

        assertThat(s.brief().kind()).isEqualTo(InstrumentKind.FUTURE);
        assertThat(s.brief().segment()).isEqualTo(MarketSegment.FUTURES);
        assertThat(s.brief().venue()).isEqualTo("FORTS");
        assertThat(s.basicAsset()).isEqualTo("USDRUB");
        assertThat(s.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_797_120_000L));
        // isin брокер для фьючерсов не отдаёт вовсе.
        assertThat(s.isin()).isNull();
    }

    @Test
    void optionHasNoFigiButKeepsUidAndStrike() {
        Option option = Option.newBuilder()
                .setUid("uid-option")
                .setTicker("SR30000BE6")
                .setName("SBER 300000 CALL")
                .setClassCode("SPBOPT")
                .setCurrency("rub")
                .setExchange("FORTS")
                .setRealExchange(RealExchange.REAL_EXCHANGE_RTS)
                .setLot(1)
                .setMinPriceIncrement(quotation(0, 10_000_000))
                .setBasicAsset("SBER")
                .setStrikePrice(MoneyValue.newBuilder().setUnits(300).setNano(500_000_000).build())
                .build();

        InstrumentSnapshot s = TInvestInstrumentsApi.mapOption(option);

        assertThat(s.brief().kind()).isEqualTo(InstrumentKind.OPTION);
        assertThat(s.brief().segment()).isEqualTo(MarketSegment.OPTIONS);
        assertThat(s.brief().id().uid()).isEqualTo("uid-option");
        // Ключ справочника — (биржа, uid) именно потому, что figi у опционов нет.
        assertThat(s.brief().id().figi()).isNull();
        assertThat(s.strikePrice()).isEqualByComparingTo(new BigDecimal("300.5"));
        // Экспирация не задана — нулевой Timestamp протобуфа не должен стать 1970 годом.
        assertThat(s.expiresAt()).isNull();
    }

    @Test
    void bondMapsMaturityAsExpiration() {
        Bond bond = Bond.newBuilder()
                .setUid("uid-bond")
                .setFigi("TCS00A109LG9")
                .setTicker("RU000A109LG9")
                .setName("Сбербанк 001Р-SBERD2")
                .setClassCode("SPBRUBND")
                .setCurrency("rub")
                .setExchange("moex_close")
                .setRealExchange(RealExchange.REAL_EXCHANGE_MOEX)
                .setLot(1)
                .setMaturityDate(Timestamp.newBuilder().setSeconds(1_900_000_000L).build())
                .build();

        InstrumentSnapshot s = TInvestInstrumentsApi.mapBond(bond);

        assertThat(s.brief().kind()).isEqualTo(InstrumentKind.BOND);
        assertThat(s.brief().segment()).isEqualTo(MarketSegment.SPOT);
        assertThat(s.expiresAt()).isEqualTo(Instant.ofEpochSecond(1_900_000_000L));
    }

    @Test
    void venueFallsBackToRealExchangeWhenRawStringIsUnknown() {
        assertThat(TInvestInstrumentsApi.normalizeVenue(RealExchange.REAL_EXCHANGE_MOEX, "")).isEqualTo("MOEX");
        assertThat(TInvestInstrumentsApi.normalizeVenue(RealExchange.REAL_EXCHANGE_RTS, "нечто")).isEqualTo("FORTS");
        assertThat(TInvestInstrumentsApi.normalizeVenue(RealExchange.REAL_EXCHANGE_DEALER, null)).isEqualTo("DEALER");
        assertThat(TInvestInstrumentsApi.normalizeVenue(RealExchange.REAL_EXCHANGE_UNSPECIFIED, "spb_close")).isEqualTo("SPB");
        assertThat(TInvestInstrumentsApi.normalizeVenue(RealExchange.REAL_EXCHANGE_UNSPECIFIED, "?")).isEqualTo("UNKNOWN");
    }

    @Test
    void currencyKeepsIsinAndSpotSegment() {
        Currency currency = Currency.newBuilder()
                .setUid("uid-usd")
                .setFigi("BBG0013HGFT4")
                .setTicker("USDRUB")
                .setName("Доллар США")
                .setClassCode("CETS")
                .setCurrency("rub")
                .setExchange("MOEX")
                .setRealExchange(RealExchange.REAL_EXCHANGE_MOEX)
                .setLot(1000)
                .setMinPriceIncrement(quotation(0, 2_500_000)) // 0.0025
                .build();

        InstrumentSnapshot s = TInvestInstrumentsApi.mapCurrency(currency);

        assertThat(s.brief().kind()).isEqualTo(InstrumentKind.CURRENCY);
        assertThat(s.brief().segment()).isEqualTo(MarketSegment.SPOT);
        assertThat(s.lot()).isEqualTo(1000);
        assertThat(s.minPriceIncrement()).isEqualByComparingTo(new BigDecimal("0.0025"));
        assertThat(s.basicAsset()).isNull();
    }
}
