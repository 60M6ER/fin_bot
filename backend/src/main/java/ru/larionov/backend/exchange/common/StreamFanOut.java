package ru.larionov.backend.exchange.common;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Раздача событий стрима нескольким получателям одного инструмента.
 *
 * Существует потому, что подписка у брокера и обработчик события живут разное время.
 * Подписка принадлежит ПОДКЛЮЧЕНИЮ и обязана пережить остановку бота: инструмент может
 * слушать сосед, и рвать ему поток нельзя. Обработчик принадлежит КОНКРЕТНОМУ ЗАПУСКУ
 * бота: он замкнут на его цикл событий и после остановки бесполезен, а хуже того —
 * вреден, потому что продолжает получать данные вместо живого.
 *
 * Инцидент 14.08.2026 родился ровно из смешения этих двух сроков жизни. SDK T-Invest
 * при повторной подписке на уже подписанный инструмент молча не регистрирует нового
 * слушателя, и перезапущенный бот навсегда оставался с обработчиком прошлой жизни:
 * снаружи живой, с актуальной ценой в карточке — и слепой к движению рынка.
 */
public final class StreamFanOut<T> {

    private final Map<String, List<Consumer<T>>> handlers = new ConcurrentHashMap<>();

    /**
     * Ставит обработчик в очередь получателей.
     *
     * @param keys         идентификаторы инструментов, как их знает адаптер
     * @param handler      получатель событий
     * @param subscribeNew вызывается ТОЛЬКО для инструментов, которых здесь ещё не было:
     *                     подписку у брокера заводим один раз, дальше лишь добавляем
     *                     получателей
     * @return снятие ИМЕННО ЭТОГО обработчика; подписку у брокера не трогает
     */
    public Runnable register(Set<String> keys, Consumer<T> handler,
                             Consumer<Set<String>> subscribeNew) {
        if (keys == null || keys.isEmpty() || handler == null) {
            return () -> { };
        }

        Set<String> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            handlers.computeIfAbsent(key, __ -> {
                fresh.add(key);
                return new CopyOnWriteArrayList<>();
            }).add(handler);
        }
        if (!fresh.isEmpty() && subscribeNew != null) {
            subscribeNew.accept(fresh);
        }

        return () -> keys.forEach(key -> {
            List<Consumer<T>> registered = handlers.get(key);
            if (registered != null) {
                registered.remove(handler);
            }
        });
    }

    /**
     * Получатели события. Ключи пробуются по порядку: у одного инструмента их два —
     * uid и figi, — а зарегистрирован он под тем, которым пользуется вызывающий.
     */
    public List<Consumer<T>> listeners(String... keys) {
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            List<Consumer<T>> registered = handlers.get(key);
            if (registered != null && !registered.isEmpty()) {
                return registered;
            }
        }
        return List.of();
    }

    /** Есть ли хоть один получатель. Для проверок и диагностики. */
    public boolean isEmpty() {
        return handlers.values().stream().allMatch(List::isEmpty);
    }

    public void clear() {
        handlers.clear();
    }
}
