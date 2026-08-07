package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.exchange.api.model.account.MoneyBalance;
import ru.larionov.backend.exchange.api.model.id.AccountId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Свободные деньги брокерского счёта, с коротким кэшем.
 *
 * Кэш здесь обязателен, а не приятен: этот баланс читают и предпросмотр сетки
 * (срабатывает по debounce на каждую пачку нажатий клавиш), и список подключений
 * (опрашивается фронтендом раз в несколько секунд). Без кэша каждый из них
 * молотил бы gRPC до упора в лимиты брокера.
 *
 * Ошибки намеренно проглатываются в пустой результат: ни форма настроек, ни список
 * не должны падать из-за того, что баланс временно недоступен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountCashService {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final ExchangeRuntimeService exchangeRuntimeService;

    private final Map<UUID, Cached> byConnection = new ConcurrentHashMap<>();

    /** Свободные деньги по валютам. Пустая карта — баланс недоступен. */
    public Map<String, BigDecimal> availableByCurrency(UUID connectionId) {
        if (connectionId == null) {
            return Map.of();
        }
        Cached cached = byConnection.get(connectionId);
        Instant now = Instant.now();
        if (cached != null && cached.until().isAfter(now)) {
            return cached.byCurrency();
        }

        Optional<ExchangeHandler> handler = exchangeRuntimeService.get(connectionId);
        if (handler.isEmpty()) {
            // Подключение не поднято — спрашивать некого. Отрицательный результат
            // тоже кэшируем, иначе каждый опрос списка будет ходить сюда впустую.
            byConnection.put(connectionId, new Cached(Map.of(), now.plus(TTL)));
            return Map.of();
        }

        try {
            AccountId accountId = handler.get().tradingAccountId();
            Map<String, BigDecimal> result = new HashMap<>();
            for (MoneyBalance balance : handler.get().client().accounts().getState(accountId).balances()) {
                if (balance.currency() != null && balance.available() != null) {
                    result.merge(normalize(balance.currency()), balance.available(), BigDecimal::add);
                }
            }
            byConnection.put(connectionId, new Cached(Map.copyOf(result), now.plus(TTL)));
            return result;
        } catch (Exception e) {
            log.debug("Не удалось получить свободные деньги подключения {}: {}", connectionId, e.getMessage());
            byConnection.put(connectionId, new Cached(Map.of(), now.plus(TTL)));
            return Map.of();
        }
    }

    /** Свободные деньги в конкретной валюте, null — неизвестно. */
    public BigDecimal available(UUID connectionId, String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        return availableByCurrency(connectionId).get(normalize(currency));
    }

    /**
     * Валюта, в которой на подключении лежат ДЕНЬГИ.
     *
     * Нужна как запасной вариант, когда валюту не подсказал ни один бот: у бота
     * без единой сделки денежная книга пуста и валюты в ней ещё нет.
     *
     * Раньше здесь выбиралась валюта с наибольшим остатком — и это было сравнением
     * несравнимого. На спотовом кошельке рядом с 0.9 USDT лежит пыль из десятка
     * монет, и 10 HTX «побеждали» по числу, после чего баланс подключения
     * показывался в HTX, а свести портфель не удавалось вовсе: курса такой монеты
     * никто не знает. Поэтому выбор идёт только среди расчётных валют биржи,
     * а монеты в этом качестве не рассматриваются: они товар, а не деньги.
     */
    public String dominantCurrency(UUID connectionId) {
        Map<String, BigDecimal> balances = availableByCurrency(connectionId);
        List<String> cashCurrencies = cashCurrencies(connectionId);

        if (!cashCurrencies.isEmpty()) {
            // Порядок объявления — это приоритет: у брокера основной счёт рублёвый,
            // у Poloniex расчёты идут в USDT. Берём первую, где деньги реально есть.
            for (String currency : cashCurrencies) {
                BigDecimal amount = balances.get(currency);
                if (amount != null && amount.signum() > 0) {
                    return currency;
                }
            }
            // Денег нет ни в одной расчётной валюте. Честный ответ — основная валюта
            // биржи с нулём, а не случайная монета из остатков.
            return cashCurrencies.getFirst();
        }

        // Биржа расчётных валют не объявила — сравнивать больше нечем.
        return balances.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Расчётные валюты биржи. Пустой список — подключение не поднято или биржа молчит. */
    private List<String> cashCurrencies(UUID connectionId) {
        try {
            return exchangeRuntimeService.get(connectionId)
                    .map(h -> h.client().meta().cashCurrencies())
                    .orElseGet(List::of);
        } catch (Exception e) {
            log.debug("Не удалось узнать расчётные валюты подключения {}: {}", connectionId, e.getMessage());
            return List.of();
        }
    }

    public void invalidate(UUID connectionId) {
        byConnection.remove(connectionId);
    }

    private static String normalize(String currency) {
        return currency.toUpperCase(Locale.ROOT);
    }

    private record Cached(Map<String, BigDecimal> byCurrency, Instant until) {
    }
}
