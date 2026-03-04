package ru.larionov.backend.exchange.tinvest;

import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.*;
import ru.tinkoff.piapi.contract.v1.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * T-Invest implementation of InstrumentsApi.
 *
 * This class adapts Tinkoff gRPC DTOs to our domain model.
 */
public class TInvestInstrumentsApi implements InstrumentsApi {

    private final TInvestExchangeClient client;

    public TInvestInstrumentsApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public List<InstrumentBrief> list(InstrumentsQuery query) {
        Objects.requireNonNull(query);

        List<InstrumentBrief> result = new ArrayList<>();

        Set<InstrumentKind> kinds = query.kinds();
        boolean needShares = (kinds == null || kinds.isEmpty() || kinds.contains(InstrumentKind.SHARE));

        if (needShares) {
            InstrumentsRequest request = InstrumentsRequest.getDefaultInstance();

            SharesResponse response = client.instrumentsStub()
                    .callSyncMethod(
                            InstrumentsServiceGrpc.getSharesMethod(),
                            stub -> stub.shares(request)
                    );

            String tickerFilter = query.ticker();
            String textFilter = query.query();
            boolean onlyTradable = query.onlyTradable();

            String tickerFilterLc = tickerFilter == null ? null : tickerFilter.trim().toLowerCase(Locale.ROOT);
            String textFilterLc = textFilter == null ? null : textFilter.trim().toLowerCase(Locale.ROOT);

            for (Share share : response.getInstrumentsList()) {
                if (onlyTradable && !share.getApiTradeAvailableFlag()) {
                    continue;
                }

                if (tickerFilterLc != null && !tickerFilterLc.isEmpty()) {
                    String t = share.getTicker() == null ? "" : share.getTicker().toLowerCase(Locale.ROOT);
                    if (!t.equals(tickerFilterLc)) {
                        continue;
                    }
                }

                if (textFilterLc != null && !textFilterLc.isEmpty()) {
                    String t = share.getTicker() == null ? "" : share.getTicker().toLowerCase(Locale.ROOT);
                    String n = share.getName() == null ? "" : share.getName().toLowerCase(Locale.ROOT);
                    if (!t.contains(textFilterLc) && !n.contains(textFilterLc)) {
                        continue;
                    }
                }

                result.add(mapShare(share));
            }
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

    private InstrumentBrief mapShare(Share share) {
        InstrumentId instrumentId = new InstrumentId(
                share.getUid(),
                share.getFigi()
        );

        return new InstrumentBrief(
                instrumentId,
                InstrumentKind.SHARE,
                share.getTicker(),
                share.getName(),
                share.getClassCode(),
                share.getCurrency()
        );
    }

    private InstrumentDetails mapDetails(Instrument instrument) {
        InstrumentId instrumentId = new InstrumentId(
                instrument.getUid(),
                instrument.getFigi()
        );

        InstrumentBrief brief = new InstrumentBrief(
                instrumentId,
                mapKind(instrument.getInstrumentType()),
                instrument.getTicker(),
                instrument.getName(),
                instrument.getClassCode(),
                instrument.getCurrency()
        );

        BigDecimal mpi = quotationToBigDecimal(instrument.getMinPriceIncrement());

        return new InstrumentDetails(
                brief,
                instrument.getLot(),
                mpi,
                instrument.getBuyAvailableFlag(),
                instrument.getSellAvailableFlag(),
                instrument.getApiTradeAvailableFlag()
        );
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

        if (u.equals("SHARE") || u.endsWith("_SHARE") || u.contains("SHARE")) {
            return InstrumentKind.SHARE;
        }
        if (u.equals("BOND") || u.endsWith("_BOND") || u.contains("BOND")) {
            return InstrumentKind.BOND;
        }
        if (u.equals("ETF") || u.endsWith("_ETF") || u.contains("ETF")) {
            return InstrumentKind.ETF;
        }
        if (u.equals("CURRENCY") || u.endsWith("_CURRENCY") || u.contains("CURRENCY")) {
            return InstrumentKind.CURRENCY;
        }
        if (u.equals("FUTURE") || u.equals("FUTURES") || u.endsWith("_FUTURE") || u.endsWith("_FUTURES") || u.contains("FUTURE")) {
            return InstrumentKind.FUTURE;
        }
        if (u.equals("OPTION") || u.endsWith("_OPTION") || u.contains("OPTION")) {
            return InstrumentKind.OPTION;
        }

        return InstrumentKind.OTHER;
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
}
