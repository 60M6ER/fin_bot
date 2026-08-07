package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Market;
import lombok.RequiredArgsConstructor;
import ru.larionov.backend.exchange.api.InstrumentsApi;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentDetails;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentSnapshot;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentsQuery;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Справочник спотовых пар Poloniex.
 *
 * Состояние пары ({@code NORMAL}, {@code PAUSE}, {@code POST_ONLY}) переносится
 * в справочник как есть: {@code active} значит «пара существует», а торгуется ли она
 * прямо сейчас — вопрос к {@code MarketDataApi.getTradingStatus}. Смешивать эти два
 * вопроса нельзя: пауза на бирже длится минуты, а справочник обновляется часами,
 * и бот, доверившийся справочнику, всю паузу долбился бы заявками впустую.
 */
@RequiredArgsConstructor
public class PoloniexInstrumentsApi implements InstrumentsApi {

    private final PoloniexSymbols symbols;

    @Override
    public List<InstrumentSnapshot> listAll(Set<InstrumentKind> kinds) {
        if (kinds != null && !kinds.isEmpty() && !kinds.contains(InstrumentKind.CRYPTO_SPOT)) {
            // Ничего кроме спота адаптер не отдаёт, и притворяться иначе не будет.
            return List.of();
        }
        return symbols.all().stream().map(PoloniexInstrumentsApi::toSnapshot).toList();
    }

    @Override
    public List<InstrumentBrief> list(InstrumentsQuery q) {
        String needle = q == null ? null : firstNonBlank(q.ticker(), q.query());
        String lower = needle == null ? null : needle.toLowerCase(Locale.ROOT);

        return symbols.all().stream()
                .filter(m -> lower == null
                        || m.getSymbol().toLowerCase(Locale.ROOT).contains(lower)
                        || (m.getDisplayName() != null
                            && m.getDisplayName().toLowerCase(Locale.ROOT).contains(lower)))
                .filter(m -> q == null || !q.onlyTradable() || tradable(m))
                .map(PoloniexInstrumentsApi::toBrief)
                .toList();
    }

    @Override
    public InstrumentDetails get(InstrumentId id) {
        return toSnapshot(market(id)).toDetails();
    }

    @Override
    public TradingConstraints getConstraints(InstrumentId id) {
        String symbol = PoloniexSymbols.symbolOf(id);
        PoloniexSymbols.Limits limits = symbols.limits(symbol);

        // Заявочная единица — сама монета, поэтому exchangeLotSize = 1, а дробить
        // её можно до quantityStep. Именно это отличие от биржевого лота и делало
        // криптобиржу невозможной, пока количество было целым.
        return new TradingConstraints(
                java.math.BigDecimal.ONE,
                limits.quantityStep(),
                limits.minQuantity(),
                limits.minAmount(),
                limits.priceStep(),
                limits.quote());
    }

    private Market market(InstrumentId id) {
        String symbol = PoloniexSymbols.symbolOf(id);
        return symbols.find(symbol).orElseThrow(() ->
                new IllegalStateException("Инструмент " + symbol + " не найден на Poloniex"));
    }

    private static boolean tradable(Market market) {
        return "NORMAL".equalsIgnoreCase(market.getState());
    }

    private static InstrumentBrief toBrief(Market market) {
        return new InstrumentBrief(
                new InstrumentId(PoloniexSymbols.uidOf(market.getSymbol()), null),
                InstrumentKind.CRYPTO_SPOT,
                MarketSegment.SPOT,
                market.getSymbol(),
                market.getDisplayName() == null ? market.getSymbol() : market.getDisplayName(),
                null,
                "POLONIEX",
                market.getQuoteCurrencyName());
    }

    private static InstrumentSnapshot toSnapshot(Market market) {
        PoloniexSymbols.Limits limits = PoloniexSymbols.Limits.of(market);
        boolean tradable = tradable(market);

        return new InstrumentSnapshot(
                toBrief(market),
                // В справочнике лот всегда 1: у крипты заявочная единица — монета.
                // Реальный шаг количества живёт в getConstraints, который бот
                // спрашивает живьём при старте, а не берёт из справочника.
                1,
                limits.priceStep(),
                tradable,
                tradable,
                true,
                // Шорт на споте не предусмотрен, выходных у биржи нет.
                false,
                true,
                market.getState(),
                null,
                "POLONIEX",
                null,
                null,
                null);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
