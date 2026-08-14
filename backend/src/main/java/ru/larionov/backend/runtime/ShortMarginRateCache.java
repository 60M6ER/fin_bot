package ru.larionov.backend.runtime;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ставка риска короткой позиции, спрошенная у брокера при старте бота.
 *
 * По форме — тот же приём, что и {@link LastPriceCache}: величина приходит в момент,
 * когда до неё есть доступ (запуск бота, где уже читаются лотность и шаг цены), а
 * нужна позже и в другом месте — при оценке, которая работает и для остановленного
 * бота. Тащить ради неё биржевой клиент в слой отчётности значило бы ходить в сеть
 * на каждый опрос списка.
 *
 * <h3>Почему не в справочник инструментов</h3>
 * Ставки риска брокер меняет по мере надобности, а справочник обновляется по
 * расписанию. Протухшая ставка хуже отсутствующей: она выглядит как знание, и по
 * ней посчиталось бы обеспечение, которого не хватит. Поэтому здесь живёт ровно то,
 * что брокер сказал НАМ и НЕДАВНО, а незнание честно остаётся незнанием.
 *
 * Ключ — инструмент, а не бот: ставка принадлежит бумаге, и два бота на одной бумаге
 * получают от брокера одно и то же число.
 */
@Component
public class ShortMarginRateCache {

    private final ConcurrentHashMap<String, BigDecimal> byInstrument = new ConcurrentHashMap<>();

    /** Записывается при сборке бота, когда ограничения инструмента только что получены. */
    public void put(String instrumentKey, BigDecimal shortInitialMarginRate) {
        if (instrumentKey == null || instrumentKey.isBlank()) {
            return;
        }
        if (shortInitialMarginRate == null || shortInitialMarginRate.signum() <= 0) {
            // Отсутствие ставки — это её отсутствие, а не ноль. Ноль означал бы
            // «обеспечения не требуется», то есть ровно противоположное правде.
            byInstrument.remove(instrumentKey);
            return;
        }
        byInstrument.put(instrumentKey, shortInitialMarginRate);
    }

    /** Пусто — брокер ставку не сообщил или бот с этой бумагой ни разу не поднимался. */
    public Optional<BigDecimal> get(String instrumentKey) {
        return instrumentKey == null ? Optional.empty()
                : Optional.ofNullable(byInstrument.get(instrumentKey));
    }

    /** Для симметрии с остальными кэшами: бот удалён — держать нечего. */
    public void evict(String instrumentKey) {
        if (instrumentKey != null) {
            byInstrument.remove(instrumentKey);
        }
    }

    /**
     * Сколько обеспечения занимает короткая позиция такого номинала.
     *
     * Пусто означает «не знаем ставку», и это НЕ то же самое, что ноль: ноль —
     * законный ответ для бота без короткой позиции, а незнание обязано выглядеть
     * незнанием и в интерфейсе тоже.
     */
    public Optional<BigDecimal> requiredMargin(String instrumentKey, BigDecimal notional) {
        if (notional == null || notional.signum() <= 0) {
            return Optional.of(BigDecimal.ZERO);
        }
        return get(instrumentKey).map(rate -> notional.multiply(rate));
    }
}
