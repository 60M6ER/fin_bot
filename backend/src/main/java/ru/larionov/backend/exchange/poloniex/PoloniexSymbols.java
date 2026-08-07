package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.SymbolTradeLimit;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Соответствие между нашим uid инструмента и символом Poloniex, плюс торговые лимиты.
 *
 * <h3>Про uid</h3>
 * Своего стабильного идентификатора у Poloniex нет — есть символ вида {@code BTC_USDT}.
 * Синтезируем uid как {@code POLONIEX:BTC_USDT}: ровно тот формат, который описан
 * в контракте {@code InstrumentsApi}. Префикс обязателен — без него uid криптобиржи
 * мог бы совпасть с чужим тикером, а ключ справочника у нас (биржа, uid).
 *
 * <h3>Про кэш</h3>
 * Масштабы цены и количества нужны на КАЖДОЙ постановке заявки: заявку с лишним
 * знаком биржа просто отвергнет. Ходить за ними по сети каждый раз нельзя, поэтому
 * держим весь справочник в памяти и обновляем раз в час. Список символов меняется
 * редко — куда реже, чем бот выставляет ордера.
 */
@Slf4j
public final class PoloniexSymbols {

    public static final String UID_PREFIX = "POLONIEX:";

    private static final Duration TTL = Duration.ofHours(1);

    private final PoloniexRest rest;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

    public PoloniexSymbols(PoloniexRest rest) {
        this.rest = rest;
    }

    public static String uidOf(String symbol) {
        return UID_PREFIX + symbol.toUpperCase(Locale.ROOT);
    }

    /**
     * Символ биржи из нашего идентификатора.
     *
     * Принимает и uid с префиксом, и голый символ: во внутренних вызовах встречается
     * и то и другое, а падать на этом посреди постановки заявки — плохой размен.
     */
    public static String symbolOf(InstrumentId id) {
        String raw = id == null ? null : id.primary();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Не задан инструмент Poloniex");
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return value.startsWith(UID_PREFIX) ? value.substring(UID_PREFIX.length()) : value;
    }

    public List<Market> all() {
        return current().markets();
    }

    public Optional<Market> find(String symbol) {
        return Optional.ofNullable(current().bySymbol().get(symbol.toUpperCase(Locale.ROOT)));
    }

    /**
     * Лимиты символа. Отсутствие символа — не повод молча торговать вслепую:
     * без масштаба количества любая заявка ушла бы с произвольным числом знаков.
     */
    public Limits limits(String symbol) {
        Market market = find(symbol).orElseThrow(() -> new IllegalStateException(
                "Инструмент " + symbol + " не найден в справочнике Poloniex"));
        return Limits.of(market);
    }

    /** Сбрасывает кэш: нужен после того, как биржа сообщила о неизвестном символе. */
    public void invalidate() {
        snapshot.set(null);
    }

    private Snapshot current() {
        Snapshot cached = snapshot.get();
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached;
        }

        List<Market> markets = rest.call("справочник рынков", rest.api().markets());
        if (markets == null || markets.isEmpty()) {
            if (cached != null) {
                // Пустой ответ при живом кэше — почти наверняка сбой на той стороне.
                // Старый справочник лучше пустого: символы меняются редко.
                log.warn("Poloniex вернул пустой справочник рынков, оставляю прежний");
                return cached;
            }
            throw new IllegalStateException("Poloniex вернул пустой справочник рынков");
        }

        Map<String, Market> bySymbol = new HashMap<>();
        for (Market market : markets) {
            if (market.getSymbol() != null) {
                bySymbol.put(market.getSymbol().toUpperCase(Locale.ROOT), market);
            }
        }

        Snapshot fresh = new Snapshot(List.copyOf(markets), Map.copyOf(bySymbol), Instant.now().plus(TTL));
        snapshot.set(fresh);
        return fresh;
    }

    private record Snapshot(List<Market> markets, Map<String, Market> bySymbol, Instant until) {
    }

    /**
     * Торговые ограничения символа, приведённые к числам.
     *
     * @param priceStep    шаг цены: 10^−priceScale
     * @param quantityStep шаг количества базовой монеты: 10^−quantityScale
     * @param minQuantity  минимальное количество в заявке
     * @param minAmount    минимальная СУММА заявки в валюте котировки. У Poloniex это
     *                     реально работающее ограничение: мелкая заявка будет отвергнута
     */
    public record Limits(
            int priceScale,
            int quantityScale,
            BigDecimal priceStep,
            BigDecimal quantityStep,
            BigDecimal minQuantity,
            BigDecimal minAmount,
            String base,
            String quote
    ) {

        static Limits of(Market market) {
            SymbolTradeLimit limit = market.getSymbolTradeLimit();
            int priceScale = limit == null || limit.getPriceScale() == null ? 8 : limit.getPriceScale();
            int quantityScale = limit == null || limit.getQuantityScale() == null ? 8 : limit.getQuantityScale();

            return new Limits(
                    priceScale,
                    quantityScale,
                    BigDecimal.ONE.movePointLeft(priceScale),
                    BigDecimal.ONE.movePointLeft(quantityScale),
                    limit == null ? null : decimal(limit.getMinQuantity()),
                    limit == null ? null : decimal(limit.getMinAmount()),
                    market.getBaseCurrencyName(),
                    market.getQuoteCurrencyName());
        }

        private static BigDecimal decimal(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Цена в том виде, в каком её примет биржа: лишние знаки — отказ. */
        public String formatPrice(BigDecimal price) {
            return price.setScale(priceScale, java.math.RoundingMode.HALF_UP).toPlainString();
        }

        /**
         * Количество для биржи, округлённое ВНИЗ.
         *
         * Вниз, потому что вверх означало бы заявку чуть больше той, что прошла
         * риск-контроль и обеспечена бюджетом.
         */
        public String formatQuantity(BigDecimal quantity) {
            return quantity.setScale(quantityScale, java.math.RoundingMode.DOWN).toPlainString();
        }
    }
}
