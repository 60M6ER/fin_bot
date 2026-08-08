package ru.larionov.backend.runtime;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Последняя цена, которую бот увидел в стриме.
 *
 * Нужна для рыночной оценки открытых лотов: {@link BotEventLoop} цену не хранит —
 * его {@code latestPrice} это слот очереди, который забирается через
 * {@code getAndSet(null)}, а {@code GridStrategy.lastPrice} приватное поле стратегии.
 * Без этого кэша ответить на вопрос «сколько сейчас стоит позиция бота» можно было бы
 * только синхронным запросом к бирже на каждый опрос списка.
 *
 * Ключ — бот, а НЕ инструмент. Два бота на одном инструменте могут смотреть на разные
 * цены: при {@code priceSource = ORDER_BOOK} хендлер считает середину стакана, при
 * {@code LAST_PRICE} берёт цену сделки. С ключом по инструменту они затирали бы друг
 * друга, и показанная оценка молча перестала бы соответствовать той цене, по которой
 * бот на самом деле принимает решения.
 */
@Component
public class LastPriceCache {

    /**
     * @param exchangeTs время события по данным биржи (может быть null)
     * @param receivedAt время получения нами — именно оно определяет свежесть,
     *                   потому что не зависит от расхождения часов с биржей
     */
    public record CachedPrice(BigDecimal price, Instant exchangeTs, Instant receivedAt) {
    }

    private final ConcurrentHashMap<UUID, CachedPrice> byBot = new ConcurrentHashMap<>();

    /**
     * Та же цена, но под ключом инструмента.
     *
     * Второй индекс нужен оценке кошелька: чтобы перевести остаток монеты в расчётную
     * валюту, нужна цена ПАРЫ, а не цена «того, на что смотрит бот номер такой-то».
     * Ключ по боту для этого не годится — спрашивающий не знает, какой бот торгует
     * DOGE_USDT и торгует ли его вообще.
     *
     * Здесь затирание друг друга (два бота на одной паре с разным priceSource)
     * безвредно ровно потому, чем оно вредно для {@link #byBot}: оценке кошелька
     * нужна рыночная цена пары, а не та, по которой принимает решения конкретный бот.
     */
    private final ConcurrentHashMap<String, CachedPrice> byInstrument = new ConcurrentHashMap<>();

    /**
     * Пишется из потоков gRPC-стрима, читается из потоков HTTP.
     * Последняя запись побеждает: для «текущей цены» это и есть нужная семантика.
     */
    public void put(UUID botId, String instrumentKey, BigDecimal price, Instant exchangeTs) {
        if (price == null) {
            return;
        }
        CachedPrice cached = new CachedPrice(price, exchangeTs, Instant.now());
        if (botId != null) {
            byBot.put(botId, cached);
        }
        if (instrumentKey != null && !instrumentKey.isBlank()) {
            byInstrument.put(instrumentKey, cached);
        }
    }

    public Optional<CachedPrice> get(UUID botId) {
        return botId == null ? Optional.empty() : Optional.ofNullable(byBot.get(botId));
    }

    /** Цена инструмента, кем бы из ботов она ни была получена. */
    public Optional<CachedPrice> getByInstrument(String instrumentKey) {
        return instrumentKey == null || instrumentKey.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(byInstrument.get(instrumentKey));
    }

    /**
     * Вызывается только при удалении бота.
     *
     * При остановке бота цену намеренно НЕ выселяем: подписки живут на уровне
     * подключения и переживают остановку (см. StrategyBotHandler#subscribeStreams),
     * поэтому остановленный бот на живом подключении продолжает получать свежие цены,
     * и его строка в списке остаётся правдивой.
     */
    public void evict(UUID botId) {
        if (botId != null) {
            byBot.remove(botId);
        }
    }
}
