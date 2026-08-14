package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.exchange.api.MarginApi;
import ru.larionov.backend.exchange.api.model.account.MarginAttributes;
import ru.larionov.backend.exchange.api.model.id.AccountId;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Маржинальные показатели счёта, с очень коротким кэшем.
 *
 * Кэш по форме такой же, как в {@link AccountCashService}, но срок жизни намеренно
 * короче: остаток счёта меняется сделками, а обеспечение — ещё и переоценкой позиций,
 * то есть непрерывно. Тридцать секунд, приемлемые для баланса, здесь означали бы
 * решение о закрытии позиции по получасовой давности данным.
 *
 * Кэш всё же нужен: показатели спрашивает и сторож на каждом тике каждого бота, и
 * риск-контроль перед каждой заявкой, и интерфейс раз в четыре секунды. Без него
 * десяток ботов на одном счёте выбрал бы лимиты брокера на ровном месте, запрашивая
 * одно и то же число.
 *
 * <h3>Почему ошибка — это Optional.empty(), а не исключение</h3>
 * Отсутствие ответа и «обеспечения не хватает» — разные вещи, и путать их нельзя
 * в обе стороны. Пустой ответ означает «не знаем»: сторож на нём молчит, а
 * риск-контроль, наоборот, обязан не пропустить заявку. Решать это здесь
 * неправильно — у читателей разная цена ошибки.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginAttributesService {

    private static final Duration TTL = Duration.ofSeconds(5);

    private final ExchangeRuntimeService exchangeRuntimeService;

    private final ConcurrentMap<Key, Cached> cache = new ConcurrentHashMap<>();

    /**
     * @return пусто, если подключение не поднято, площадка не умеет отвечать
     *         про обеспечение или биржа промолчала
     */
    public Optional<MarginAttributes> get(UUID connectionId, AccountId accountId) {
        if (connectionId == null || accountId == null) {
            return Optional.empty();
        }
        Key key = new Key(connectionId, accountId.value());
        Instant now = Instant.now();

        Cached cached = cache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return Optional.ofNullable(cached.attributes());
        }

        Optional<MarginApi> api = exchangeRuntimeService.get(connectionId)
                .flatMap(handler -> handler.client().margin());
        if (api.isEmpty()) {
            // Спрашивать некого: подключение не поднято либо площадка про маржу
            // ничего не знает. Отрицательный результат кэшируем наравне с
            // положительным — иначе каждый тик ходил бы сюда впустую.
            cache.put(key, new Cached(null, now.plus(TTL)));
            return Optional.empty();
        }

        try {
            MarginAttributes attributes = api.get().getMarginAttributes(accountId);
            cache.put(key, new Cached(attributes, now.plus(TTL)));
            return Optional.ofNullable(attributes);
        } catch (Exception e) {
            log.debug("Не удалось получить маржинальные показатели счёта {}: {}",
                    accountId.value(), e.getMessage());
            cache.put(key, new Cached(null, now.plus(TTL)));
            return Optional.empty();
        }
    }

    /** Умеет ли площадка вообще отвечать про обеспечение. */
    public boolean supported(UUID connectionId) {
        return exchangeRuntimeService.get(connectionId)
                .map(handler -> handler.client().margin().isPresent())
                .orElse(false);
    }

    public void invalidate(UUID connectionId) {
        cache.keySet().removeIf(key -> key.connectionId().equals(connectionId));
    }

    private record Key(UUID connectionId, String accountId) {
    }

    /** @param attributes null — ответа нет; кэшируется наравне с ответом */
    private record Cached(MarginAttributes attributes, Instant until) {
    }
}
