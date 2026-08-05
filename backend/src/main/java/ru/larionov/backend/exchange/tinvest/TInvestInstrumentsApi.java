package ru.larionov.backend.exchange.tinvest;

import io.grpc.MethodDescriptor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.*;
import ru.tinkoff.piapi.contract.v1.*;

import com.google.protobuf.Timestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * T-Invest implementation of InstrumentsApi.
 *
 * Адаптирует gRPC-DTO Тинькофф к нашей доменной модели. Поддерживаются все шесть типов,
 * которые брокер отдаёт списком: акции, фонды, облигации, валюты, фьючерсы, опционы.
 *
 * Протобуф не даёт этим шести сообщениям общего супертипа, а наборы полей у них реально
 * разные (у Option нет ни figi, ни isin; у Future/Option есть базовый актив и экспирация).
 * Поэтому вместо рефлексивного маппера — один сборщик {@link #snapshot} и шесть коротких
 * map*-методов, которые только достают поля: так расхождение ловится компилятором.
 */
@Slf4j
public class TInvestInstrumentsApi implements InstrumentsApi {

    /**
     * BASE, а не ALL: ограничивает выгрузку тем, что реально доступно на платформе,
     * и на порядок режет шум по опционам и облигациям.
     */
    private static final InstrumentsRequest BASE_REQUEST = InstrumentsRequest.newBuilder()
            .setInstrumentStatus(InstrumentStatus.INSTRUMENT_STATUS_BASE)
            .build();

    private static final Set<InstrumentKind> SUPPORTED_KINDS = EnumSet.of(
            InstrumentKind.SHARE, InstrumentKind.ETF, InstrumentKind.BOND,
            InstrumentKind.CURRENCY, InstrumentKind.FUTURE, InstrumentKind.OPTION);

    private final TInvestExchangeClient client;

    public TInvestInstrumentsApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public List<InstrumentSnapshot> listAll(Set<InstrumentKind> kinds) {
        Set<InstrumentKind> requested = (kinds == null || kinds.isEmpty())
                ? SUPPORTED_KINDS
                : EnumSet.copyOf(kinds);

        List<InstrumentSnapshot> result = new ArrayList<>();
        for (InstrumentKind kind : SUPPORTED_KINDS) {
            if (requested.contains(kind)) {
                result.addAll(fetchKind(kind));
            }
        }
        return result;
    }

    /**
     * Тонкий фильтр поверх полной выгрузки. Аварийный путь: UI ищет по нашему справочнику,
     * а сюда попадает только резолв uid в подпись, когда справочник ещё пуст.
     */
    @Override
    public List<InstrumentBrief> list(InstrumentsQuery query) {
        Objects.requireNonNull(query);

        String tickerFilter = lower(query.ticker());
        String textFilter = lower(query.query());

        List<InstrumentBrief> result = new ArrayList<>();
        for (InstrumentSnapshot s : listAll(query.kinds())) {
            if (query.onlyTradable() && !s.apiTradeAvailable()) {
                continue;
            }
            InstrumentBrief brief = s.brief();
            String ticker = lower(brief.ticker());
            String name = lower(brief.name());

            if (tickerFilter != null && !tickerFilter.equals(ticker)) {
                continue;
            }
            if (textFilter != null
                    && !(ticker != null && ticker.contains(textFilter))
                    && !(name != null && name.contains(textFilter))) {
                continue;
            }
            result.add(brief);
        }
        return result;
    }

    @Override
    public InstrumentDetails get(InstrumentId id) {
        Objects.requireNonNull(id);

        if (id.uid() == null) {
            throw new IllegalArgumentException("T-Invest requires UID for detailed instrument lookup");
        }

        InstrumentRequest request = InstrumentRequest.newBuilder()
                .setIdType(InstrumentIdType.INSTRUMENT_ID_TYPE_UID)
                .setId(id.uid())
                .build();

        InstrumentResponse response = client.instrumentsStub()
                .callSyncMethod(
                        InstrumentsServiceGrpc.getGetInstrumentByMethod(),
                        stub -> stub.getInstrumentBy(request)
                );

        return mapDetails(response.getInstrument());
    }

    @Override
    public TradingConstraints getConstraints(InstrumentId id) {
        InstrumentDetails details = get(id);

        return new TradingConstraints(
                details.lot(),
                details.minPriceIncrement(),
                details.brief().quoteCurrency()
        );
    }

    // ------------------------------------------------------------------ выгрузка

    private List<InstrumentSnapshot> fetchKind(InstrumentKind kind) {
        return switch (kind) {
            case SHARE -> fetch(kind, InstrumentsServiceGrpc.getSharesMethod(),
                    stub -> stub.shares(BASE_REQUEST),
                    SharesResponse::getInstrumentsList, TInvestInstrumentsApi::mapShare);
            case ETF -> fetch(kind, InstrumentsServiceGrpc.getEtfsMethod(),
                    stub -> stub.etfs(BASE_REQUEST),
                    EtfsResponse::getInstrumentsList, TInvestInstrumentsApi::mapEtf);
            case BOND -> fetch(kind, InstrumentsServiceGrpc.getBondsMethod(),
                    stub -> stub.bonds(BASE_REQUEST),
                    BondsResponse::getInstrumentsList, TInvestInstrumentsApi::mapBond);
            case CURRENCY -> fetch(kind, InstrumentsServiceGrpc.getCurrenciesMethod(),
                    stub -> stub.currencies(BASE_REQUEST),
                    CurrenciesResponse::getInstrumentsList, TInvestInstrumentsApi::mapCurrency);
            case FUTURE -> fetch(kind, InstrumentsServiceGrpc.getFuturesMethod(),
                    stub -> stub.futures(BASE_REQUEST),
                    FuturesResponse::getInstrumentsList, TInvestInstrumentsApi::mapFuture);
            case OPTION -> fetchOptions();
            default -> List.of();
        };
    }

    /**
     * Полная выгрузка опционов помечена у брокера как deprecated: он предлагает optionsBy
     * с обязательным фильтром по базовому активу. Для справочника нам нужны все, поэтому
     * пользуемся тем, что есть, — но выделено отдельным методом, чтобы @SuppressWarnings
     * не глушил предупреждения по остальным пяти типам.
     *
     * Если брокер выключит метод или ответ перестанет влезать в лимит сообщения, упадёт
     * только этот тип: вызывающая синхронизация изолирует ошибки по kind.
     */
    @SuppressWarnings("deprecation")
    private List<InstrumentSnapshot> fetchOptions() {
        return fetch(InstrumentKind.OPTION, InstrumentsServiceGrpc.getOptionsMethod(),
                stub -> stub.options(BASE_REQUEST),
                OptionsResponse::getInstrumentsList, TInvestInstrumentsApi::mapOption);
    }

    private <R, I> List<InstrumentSnapshot> fetch(
            InstrumentKind kind,
            MethodDescriptor<?, R> method,
            Function<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub, R> call,
            Function<R, List<I>> extract,
            Function<I, InstrumentSnapshot> mapper) {

        R response = client.instrumentsStub().callSyncMethod(method, call);
        List<I> items = extract.apply(response);
        log.debug("T-Invest {}: получено {} инструментов", kind, items.size());
        return items.stream().map(mapper).toList();
    }

    // ------------------------------------------------------------------ маппинг

    /**
     * Единственное место, где собирается снимок. Шесть map*-методов ниже только достают поля,
     * поэтому забытое поле — ошибка компиляции, а не тихо потерянные данные.
     */
    private static InstrumentSnapshot snapshot(
            String uid, String figi, InstrumentKind kind, MarketSegment segment,
            String ticker, String name, String classCode, String currency,
            String rawExchange, RealExchange realExchange,
            int lot, Quotation minPriceIncrement,
            boolean buy, boolean sell, boolean apiTrade, boolean shortEnabled, boolean weekend,
            SecurityTradingStatus status,
            String isin, String basicAsset, Timestamp expiration, MoneyValue strike) {

        InstrumentBrief brief = new InstrumentBrief(
                new InstrumentId(blankToNull(uid), blankToNull(figi)),
                kind,
                segment,
                ticker,
                name,
                blankToNull(classCode),
                normalizeVenue(realExchange, rawExchange),
                // Брокер отдаёт валюту строчными ("rub"), а рядом в подписи стоят
                // MOEX и TQBR — вперемешку это выглядит опечаткой.
                upperOrNull(currency)
        );

        return new InstrumentSnapshot(
                brief,
                lot,
                quotationToBigDecimal(minPriceIncrement),
                buy, sell, apiTrade, shortEnabled, weekend,
                status == null ? null : status.name(),
                blankToNull(isin),
                blankToNull(rawExchange),
                blankToNull(basicAsset),
                toInstant(expiration),
                moneyToBigDecimal(strike)
        );
    }

    static InstrumentSnapshot mapShare(Share s) {
        return snapshot(s.getUid(), s.getFigi(), InstrumentKind.SHARE, MarketSegment.SPOT,
                s.getTicker(), s.getName(), s.getClassCode(), s.getCurrency(),
                s.getExchange(), s.getRealExchange(),
                s.getLot(), s.getMinPriceIncrement(),
                s.getBuyAvailableFlag(), s.getSellAvailableFlag(), s.getApiTradeAvailableFlag(),
                s.getShortEnabledFlag(), s.getWeekendFlag(), s.getTradingStatus(),
                s.getIsin(), null, null, null);
    }

    static InstrumentSnapshot mapEtf(Etf e) {
        return snapshot(e.getUid(), e.getFigi(), InstrumentKind.ETF, MarketSegment.SPOT,
                e.getTicker(), e.getName(), e.getClassCode(), e.getCurrency(),
                e.getExchange(), e.getRealExchange(),
                e.getLot(), e.getMinPriceIncrement(),
                e.getBuyAvailableFlag(), e.getSellAvailableFlag(), e.getApiTradeAvailableFlag(),
                e.getShortEnabledFlag(), e.getWeekendFlag(), e.getTradingStatus(),
                e.getIsin(), null, null, null);
    }

    static InstrumentSnapshot mapBond(Bond b) {
        return snapshot(b.getUid(), b.getFigi(), InstrumentKind.BOND, MarketSegment.SPOT,
                b.getTicker(), b.getName(), b.getClassCode(), b.getCurrency(),
                b.getExchange(), b.getRealExchange(),
                b.getLot(), b.getMinPriceIncrement(),
                b.getBuyAvailableFlag(), b.getSellAvailableFlag(), b.getApiTradeAvailableFlag(),
                b.getShortEnabledFlag(), b.getWeekendFlag(), b.getTradingStatus(),
                b.getIsin(), null, b.getMaturityDate(), null);
    }

    static InstrumentSnapshot mapCurrency(Currency c) {
        return snapshot(c.getUid(), c.getFigi(), InstrumentKind.CURRENCY, MarketSegment.SPOT,
                c.getTicker(), c.getName(), c.getClassCode(), c.getCurrency(),
                c.getExchange(), c.getRealExchange(),
                c.getLot(), c.getMinPriceIncrement(),
                c.getBuyAvailableFlag(), c.getSellAvailableFlag(), c.getApiTradeAvailableFlag(),
                c.getShortEnabledFlag(), c.getWeekendFlag(), c.getTradingStatus(),
                c.getIsin(), null, null, null);
    }

    static InstrumentSnapshot mapFuture(Future f) {
        // isin у фьючерсов брокер не отдаёт вовсе
        return snapshot(f.getUid(), f.getFigi(), InstrumentKind.FUTURE, MarketSegment.FUTURES,
                f.getTicker(), f.getName(), f.getClassCode(), f.getCurrency(),
                f.getExchange(), f.getRealExchange(),
                f.getLot(), f.getMinPriceIncrement(),
                f.getBuyAvailableFlag(), f.getSellAvailableFlag(), f.getApiTradeAvailableFlag(),
                f.getShortEnabledFlag(), f.getWeekendFlag(), f.getTradingStatus(),
                null, f.getBasicAsset(), f.getExpirationDate(), null);
    }

    static InstrumentSnapshot mapOption(Option o) {
        // У Option в контракте T-Invest нет ни figi, ни isin — поэтому ключом справочника
        // может быть только uid.
        return snapshot(o.getUid(), null, InstrumentKind.OPTION, MarketSegment.OPTIONS,
                o.getTicker(), o.getName(), o.getClassCode(), o.getCurrency(),
                o.getExchange(), o.getRealExchange(),
                o.getLot(), o.getMinPriceIncrement(),
                o.getBuyAvailableFlag(), o.getSellAvailableFlag(), o.getApiTradeAvailableFlag(),
                o.getShortEnabledFlag(), o.getWeekendFlag(), o.getTradingStatus(),
                null, o.getBasicAsset(), o.getExpirationDate(), o.getStrikePrice());
    }

    private InstrumentDetails mapDetails(Instrument instrument) {
        InstrumentKind kind = mapKind(instrument.getInstrumentType());

        InstrumentBrief brief = new InstrumentBrief(
                new InstrumentId(blankToNull(instrument.getUid()), blankToNull(instrument.getFigi())),
                kind,
                segmentOf(kind),
                instrument.getTicker(),
                instrument.getName(),
                blankToNull(instrument.getClassCode()),
                normalizeVenue(instrument.getRealExchange(), instrument.getExchange()),
                blankToNull(instrument.getCurrency())
        );

        return new InstrumentDetails(
                brief,
                instrument.getLot(),
                quotationToBigDecimal(instrument.getMinPriceIncrement()),
                instrument.getBuyAvailableFlag(),
                instrument.getSellAvailableFlag(),
                instrument.getApiTradeAvailableFlag()
        );
    }

    private static MarketSegment segmentOf(InstrumentKind kind) {
        return switch (kind) {
            case FUTURE -> MarketSegment.FUTURES;
            case OPTION -> MarketSegment.OPTIONS;
            case SHARE, ETF, BOND, CURRENCY -> MarketSegment.SPOT;
            default -> MarketSegment.OTHER;
        };
    }

    /**
     * Брокер отдаёт площадку в двух видах: enum real_exchange (MOEX/RTS/OTC/DEALER) и
     * свободную строку exchange (MOEX_EVENING_WEEKEND, FORTS_EVENING, spb_close, otc_ncc).
     * Сырую строку в выпадающем списке показывать нельзя — она нечитаема, поэтому
     * нормализуем к короткому коду, а оригинал кладём в venueRaw.
     */
    static String normalizeVenue(RealExchange real, String raw) {
        String u = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("SPB")) return "SPB";
        if (u.startsWith("FORTS")) return "FORTS";
        if (u.startsWith("MOEX")) return "MOEX";
        if (u.startsWith("OTC")) return "OTC";

        if (real == null) return "UNKNOWN";
        return switch (real) {
            case REAL_EXCHANGE_MOEX -> "MOEX";
            case REAL_EXCHANGE_RTS -> "FORTS";
            case REAL_EXCHANGE_OTC -> "OTC";
            case REAL_EXCHANGE_DEALER -> "DEALER";
            default -> "UNKNOWN";
        };
    }

    private static InstrumentKind mapKind(String instrumentType) {
        if (instrumentType == null) {
            return InstrumentKind.OTHER;
        }

        String t = instrumentType.trim();
        if (t.isEmpty()) {
            return InstrumentKind.OTHER;
        }

        // Be tolerant to different representations: "share", "bond", "INSTRUMENT_TYPE_SHARE", etc.
        String u = t.toUpperCase(Locale.ROOT);

        if (u.contains("SHARE")) {
            return InstrumentKind.SHARE;
        }
        if (u.contains("BOND")) {
            return InstrumentKind.BOND;
        }
        if (u.contains("ETF")) {
            return InstrumentKind.ETF;
        }
        if (u.contains("CURRENCY")) {
            return InstrumentKind.CURRENCY;
        }
        if (u.contains("FUTURE")) {
            return InstrumentKind.FUTURE;
        }
        if (u.contains("OPTION")) {
            return InstrumentKind.OPTION;
        }

        return InstrumentKind.OTHER;
    }

    // ------------------------------------------------------------------ утилиты

    private static String lower(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static String upperOrNull(String s) {
        String t = blankToNull(s);
        return t == null ? null : t.toUpperCase(Locale.ROOT);
    }

    /** Протобуф отдаёт "" вместо null. В БД пустая строка и отсутствие значения — разные вещи. */
    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static BigDecimal quotationToBigDecimal(Quotation q) {
        if (q == null) {
            return null;
        }
        // Quotation = units + nano (1e-9)
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        return units.add(nano);
    }

    private static BigDecimal moneyToBigDecimal(MoneyValue m) {
        if (m == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(m.getUnits());
        BigDecimal nano = BigDecimal.valueOf(m.getNano(), 9);
        return units.add(nano);
    }

    /** Протобуф отдаёт нулевой Timestamp вместо отсутствия даты. */
    private static Instant toInstant(Timestamp ts) {
        if (ts == null || (ts.getSeconds() == 0 && ts.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }
}
