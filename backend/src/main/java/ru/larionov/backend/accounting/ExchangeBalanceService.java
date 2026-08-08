package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.exchange.api.model.account.Position;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentsQuery;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.money.CurrencyCode;
import ru.larionov.backend.money.FxRateService;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.AccountCashService;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeRuntimeService;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сколько стоит подключение целиком, в его расчётной валюте.
 *
 * <h3>Что такое «баланс биржи»</h3>
 * Деньги в расчётной валюте ПЛЮС всё остальное на счёте, пересчитанное по текущей
 * цене. Купленное не перестаёт быть вашим оттого, что перестало быть деньгами, —
 * а именно так и считалось раньше: баланс подключения складывался из одних лишь
 * свободных денег, и бот, потративший бюджет на монету, выглядел как дыра в кошельке.
 * Отсюда бралось «свободно в портфеле −48.81 USDT» при реальном минусе около семи.
 *
 * Слагаемых три, и каждое закрывает свой способ ошибиться:
 * <ul>
 *   <li>деньги расчётной валюты — свободные И заблокированные заявками. Заявку
 *       можно снять, деньги под ней никуда не делись;</li>
 *   <li>прочие остатки счёта — монеты. Это товар, и в баланс они входят по цене
 *       пары «монета/расчётная валюта». Другая РАСЧЁТНАЯ валюта (доллары на рублёвом
 *       счёте) переводится курсом, а не биржевой ценой: пары для неё на этой бирже нет;</li>
 *   <li>позиции, которые биржа отдаёт отдельным списком (у брокера это бумаги;
 *       на спотовой бирже список пуст, там позиции и есть остатки монет).</li>
 * </ul>
 *
 * <h3>Откуда цены</h3>
 * Сначала из {@link LastPriceCache}: боты сидят на стримах, и цена пары, которую
 * кто-то из них торгует, уже лежит в памяти и не стоит ни одного запроса. Чего в
 * кэше нет — спрашивается у биржи и кладётся в собственный кэш на полминуты.
 * Полминуты выбраны не из точности, а из назначения: это число на экране, который
 * фронтенд опрашивает каждые несколько секунд.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeBalanceService {

    private static final Duration PRICE_TTL = Duration.ofSeconds(30);

    /** Столько живёт цена, полученная из стрима бота, прежде чем считаться протухшей. */
    private static final Duration STREAM_PRICE_TTL = Duration.ofMinutes(5);

    private final ExchangeRuntimeService exchangeRuntime;
    private final AccountCashService accountCash;
    private final LastPriceCache lastPriceCache;
    private final FxRateService fx;

    private final Map<String, CachedPrice> priceCache = new ConcurrentHashMap<>();

    /**
     * @param baseCurrency     валюта, в которой выражены суммы
     * @param cash             деньги расчётной валюты: свободные плюс заблокированные
     * @param assets           всё прочее по текущей цене
     * @param total            {@code cash + assets} — во сколько оценивается счёт
     * @param assetsByCurrency что именно лежит, в единицах самого актива: для показа
     * @param incomplete       чему-то не нашлась цена, и {@code assets} занижен
     */
    public record ExchangeBalance(
            String baseCurrency,
            BigDecimal cash,
            BigDecimal assets,
            BigDecimal total,
            Map<String, BigDecimal> assetsByCurrency,
            boolean incomplete
    ) {
        public static ExchangeBalance unknown(String baseCurrency) {
            return new ExchangeBalance(baseCurrency, null, null, null, Map.of(), true);
        }

        public boolean known() {
            return total != null;
        }
    }

    /**
     * Расчётная валюта подключения: заданная в настройках либо основная валюта биржи.
     *
     * @param configured значение из настроек подключения, может быть null
     */
    public String baseCurrency(UUID connectionId, String configured) {
        if (configured != null && !configured.isBlank()) {
            return CurrencyCode.normalize(configured);
        }
        return cashCurrencies(connectionId).stream()
                .findFirst()
                .map(CurrencyCode::normalize)
                .orElse(null);
    }

    /** Баланс подключения в его расчётной валюте. */
    public ExchangeBalance balance(UUID connectionId, String configuredBaseCurrency) {
        String base = baseCurrency(connectionId, configuredBaseCurrency);
        if (base == null) {
            return ExchangeBalance.unknown(null);
        }

        Map<String, BigDecimal> balances = accountCash.totalByCurrency(connectionId);
        List<Position> positions = accountCash.positions(connectionId);
        if (balances.isEmpty() && positions.isEmpty()) {
            // Ни денег, ни позиций — это либо пустой счёт, либо недоступная биржа.
            // Различить их здесь нечем, а показывать ноль как факт нельзя.
            return ExchangeBalance.unknown(base);
        }

        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal assets = BigDecimal.ZERO;
        Map<String, BigDecimal> assetsByCurrency = new HashMap<>();
        boolean incomplete = false;

        for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
            String currency = CurrencyCode.normalize(entry.getKey());
            BigDecimal amount = entry.getValue();
            if (amount == null || amount.signum() == 0) {
                continue;
            }
            if (CurrencyCode.sameMoney(currency, base)) {
                cash = cash.add(amount);
                continue;
            }

            assetsByCurrency.merge(currency, amount, BigDecimal::add);
            BigDecimal converted = convert(connectionId, currency, base, amount);
            if (converted == null) {
                incomplete = true;
            } else {
                assets = assets.add(converted);
            }
        }

        for (Position position : positions) {
            BigDecimal value = positionValue(connectionId, position);
            if (value == null) {
                incomplete = true;
            } else {
                assets = assets.add(value);
            }
        }

        return new ExchangeBalance(base, cash, assets, cash.add(assets),
                Map.copyOf(assetsByCurrency), incomplete);
    }

    /**
     * Пересчёт остатка в расчётную валюту.
     *
     * Порядок источников — от точного к приблизительному: биржевая цена пары знает
     * про эту биржу всё, курс ЦБ — ничего. Для монеты курса не существует вовсе,
     * для доллара на рублёвом счёте не существует пары; поэтому нужны оба.
     */
    private BigDecimal convert(UUID connectionId, String currency, String base, BigDecimal amount) {
        BigDecimal price = pairPrice(connectionId, currency, base);
        if (price != null) {
            return amount.multiply(price);
        }
        return fx.rate(currency, base)
                .map(rate -> amount.multiply(rate.rate()))
                .orElse(null);
    }

    /** Цена одной единицы {@code currency} в {@code base} по паре на этой бирже. */
    private BigDecimal pairPrice(UUID connectionId, String currency, String base) {
        String key = connectionId + "|" + currency + "|" + base;
        Instant now = Instant.now();

        CachedPrice cached = priceCache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.price();
        }

        BigDecimal price = fetchPairPrice(connectionId, currency, base);
        // Отрицательный ответ кэшируется наравне с положительным: пары «HTX/USDT»
        // может не быть в принципе, и искать её на каждый опрос списка — впустую
        // ходить в биржу раз в несколько секунд.
        priceCache.put(key, new CachedPrice(price, now.plus(PRICE_TTL)));
        return price;
    }

    private BigDecimal fetchPairPrice(UUID connectionId, String currency, String base) {
        Optional<ExchangeHandler> handler = exchangeRuntime.get(connectionId);
        if (handler.isEmpty()) {
            return null;
        }
        try {
            InstrumentBrief pair = findPair(handler.get(), currency, base);
            if (pair == null || pair.id() == null) {
                return null;
            }

            // Стрим бота — самый дешёвый источник: цена уже в памяти.
            Optional<BigDecimal> streamed = lastPriceCache.getByInstrument(pair.id().primary())
                    .filter(p -> p.receivedAt() != null
                            && p.receivedAt().isAfter(Instant.now().minus(STREAM_PRICE_TTL)))
                    .map(LastPriceCache.CachedPrice::price);
            if (streamed.isPresent()) {
                return streamed.get();
            }

            LastPrice last = handler.get().client().marketData().getLastPrice(pair.id());
            return last == null || last.price() == null ? null : last.price().value();
        } catch (Exception e) {
            log.debug("Не удалось узнать цену {}/{} на подключении {}: {}",
                    currency, base, connectionId, e.getMessage());
            return null;
        }
    }

    /**
     * Пара «монета к расчётной валюте» в справочнике биржи.
     *
     * Тикер ищется поиском, а не сборкой строки «DOGE_USDT»: разделитель у бирж
     * свой, и зашивать здесь формат Poloniex значило бы сломать всё остальное при
     * первой же новой бирже. Сверяемся с валютой котировки — она и решает.
     */
    private InstrumentBrief findPair(ExchangeHandler handler, String currency, String base) {
        List<InstrumentBrief> found = handler.client().instruments()
                .list(new InstrumentsQuery(null, null, currency, true));
        for (InstrumentBrief candidate : found) {
            if (CurrencyCode.sameMoney(candidate.quoteCurrency(), base)
                    && startsWithCurrency(candidate.ticker(), currency)) {
                return candidate;
            }
        }
        return null;
    }

    /** Монета должна быть БАЗОЙ пары: USDT_DOGE ценой доджа не является. */
    private static boolean startsWithCurrency(String ticker, String currency) {
        if (ticker == null || currency == null) {
            return false;
        }
        String upper = ticker.toUpperCase(java.util.Locale.ROOT);
        String coin = currency.toUpperCase(java.util.Locale.ROOT);
        return upper.equals(coin) || upper.startsWith(coin + "_") || upper.startsWith(coin + "-");
    }

    /** Позиция, которую биржа отдаёт со своей ценой. Без цены оценить нечем. */
    private BigDecimal positionValue(UUID connectionId, Position position) {
        if (position == null || position.quantity() == null || position.quantity().signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (position.currentPrice() != null) {
            return position.quantity().multiply(position.currentPrice());
        }
        InstrumentId id = position.instrumentId();
        if (id == null) {
            return null;
        }
        return lastPriceCache.getByInstrument(id.primary())
                .map(LastPriceCache.CachedPrice::price)
                .map(price -> price.multiply(position.quantity()))
                .orElse(null);
    }

    private List<String> cashCurrencies(UUID connectionId) {
        try {
            return exchangeRuntime.get(connectionId)
                    .map(h -> h.client().meta().cashCurrencies())
                    .orElseGet(List::of);
        } catch (Exception e) {
            log.debug("Не удалось узнать расчётные валюты подключения {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    /** @param price null — цены нет; отрицательный ответ кэшируется наравне с положительным */
    private record CachedPrice(BigDecimal price, Instant until) {
    }
}
