package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.event.spot.PoloEvent;
import com.poloniex.api.client.spot.model.event.spot.TickerEvent;
import com.poloniex.api.client.spot.ws.PoloApiCallback;
import com.poloniex.api.client.spot.ws.spot.SpotPoloPublicWebsocketClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.WebSocket;
import ru.larionov.backend.exchange.api.MarketDataStreamService;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.exchange.common.StreamHealthTracker;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Публичный поток рыночных данных Poloniex.
 *
 * Подписка на тикер, а не на стакан: сетке достаточно последней цены, а событий
 * по стакану на порядок больше — на криптобирже это заметная разница в нагрузке
 * на очередь событий бота.
 *
 * <h3>Про переподключение</h3>
 * Вебсокет рвётся — это обычная его жизнь, а не исключительная ситуация. Раньше
 * адаптер об обрыве только СООБЩАЛ: писал в лог, помечал здоровье и дёргал хуки, —
 * но подписку не восстанавливал. 09.08.2026 стримы оборвались, и оба бота простояли
 * до тех пор, пока подключение не перезапустили руками: снаружи подключение выглядело
 * рабочим, потому что REST-вызовы у него живы.
 *
 * Теперь подписка помнит себя и поднимается заново с нарастающей паузой. Хуки
 * {@link #onReconnect} вызываются ПОСЛЕ успешного восстановления, а не в момент
 * обрыва: за время разрыва события потерялись безвозвратно, и сверка нужна тогда,
 * когда есть с чем сверяться.
 *
 * Сторож подключения в RuntimeSupervisor при этом остаётся: он страхует случай,
 * когда переподключиться не удаётся вовсе.
 */
@Slf4j
public class PoloniexMarketDataStreamService implements MarketDataStreamService {

    /** Первая пауза перед повтором. Дальше удваивается до {@link #RECONNECT_MAX}. */
    private static final Duration RECONNECT_MIN = Duration.ofSeconds(1);

    /**
     * Потолок паузы. Минута выбрана по цене ошибки: биржа, лежащая дольше минуты,
     * от более частых попыток не поднимется, а бот и так уже не торгует.
     */
    private static final Duration RECONNECT_MAX = Duration.ofMinutes(1);

    private final SpotPoloPublicWebsocketClient client;
    private final StreamHealthTracker health = new StreamHealthTracker();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();

    /** Сервис закрыт насовсем: обрывы в этот момент ожидаемы и восстановления не требуют. */
    private volatile boolean stopping;

    private volatile ScheduledExecutorService retries;

    public PoloniexMarketDataStreamService(String wsUrl) {
        this(new SpotPoloPublicWebsocketClient(wsUrl));
    }

    /** Шов для теста: восстановление подписки иначе не проверить без живой биржи. */
    PoloniexMarketDataStreamService(SpotPoloPublicWebsocketClient client) {
        this.client = client;
    }

    @Override
    public void subscribeLastPrice(Set<InstrumentId> instruments, Consumer<LastPrice> handler) {
        List<String> symbols = symbols(instruments);
        if (symbols.isEmpty()) {
            return;
        }
        Subscription subscription = new Subscription(symbols, handler);
        subscriptions.add(subscription);
        open(subscription, false);
    }

    /**
     * @param afterBreak true — это восстановление после обрыва: о нём надо сообщить
     *                   подписчикам, чтобы они сверились с биржей
     */
    private void open(Subscription subscription, boolean afterBreak) {
        try {
            subscription.socket = client.onTickerEvent(subscription.symbols,
                    new PoloApiCallback<TickerEvent>() {
                        @Override
                        public void onResponse(PoloEvent<TickerEvent> event) {
                            health.markEvent();
                            if (event == null || event.getData() == null) {
                                return;
                            }
                            for (TickerEvent ticker : event.getData()) {
                                LastPrice price = toLastPrice(ticker);
                                if (price != null) {
                                    subscription.handler.accept(price);
                                }
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            health.markError(t == null ? "разрыв соединения" : t.getMessage());
                            scheduleReconnect(subscription, t);
                        }
                    });
            health.markConnected();
            subscription.attempt = 0;
            if (afterBreak) {
                log.info("Стрим Poloniex восстановлен: {}", subscription.symbols);
                reconnectHandlers.forEach(PoloniexMarketDataStreamService::runQuietly);
            }
        } catch (Exception e) {
            health.markError(e.getMessage());
            scheduleReconnect(subscription, e);
        }
    }

    /**
     * Ставит одну — и только одну — попытку восстановления на подписку.
     *
     * Обрыв приходит по всем сокетам сразу, и без этого флага каждая пришедшая
     * ошибка заводила бы собственный цикл повторов.
     */
    private void scheduleReconnect(Subscription subscription, Throwable cause) {
        if (stopping || subscription.cancelled
                || !subscription.reconnecting.compareAndSet(false, true)) {
            return;
        }
        long delayMs = Math.min(
                RECONNECT_MIN.toMillis() << Math.min(subscription.attempt, 6),
                RECONNECT_MAX.toMillis());
        subscription.attempt++;
        log.warn("Стрим рыночных данных Poloniex оборвался ({}), повтор через {} мс: {}",
                cause == null ? "без причины" : cause.getMessage(), delayMs, subscription.symbols);

        try {
            retries().schedule(() -> {
                subscription.reconnecting.set(false);
                // Подписку могли снять, пока мы ждали: поднимать её заново — значит
                // завести поток, которого никто не просил и который некому закрыть.
                if (stopping || subscription.cancelled) {
                    return;
                }
                closeQuietly(subscription.socket);
                open(subscription, true);
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Планировщик мог быть уже погашен закрытием сервиса — это не беда.
            subscription.reconnecting.set(false);
            log.debug("Повтор подписки Poloniex не запланирован: {}", e.getMessage());
        }
    }

    private ScheduledExecutorService retries() {
        ScheduledExecutorService existing = retries;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (retries == null) {
                retries = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "poloniex-md-reconnect");
                    t.setDaemon(true);
                    return t;
                });
            }
            return retries;
        }
    }

    /**
     * Стакан адаптером не транслируется.
     *
     * Осознанно: сетке хватает последней цены, а подписка на стакан ради середины
     * спреда дала бы на порядок больше событий. Бот с priceSource=ORDER_BOOK на
     * Poloniex просто не получит потока — и это лучше, чем тихо подсунуть ему
     * тикер под видом стакана.
     */
    @Override
    public void subscribeOrderBook(Set<InstrumentId> instruments, int depth, Consumer<OrderBook> handler) {
        log.info("Poloniex: поток стакана не поддержан адаптером, используйте priceSource=LAST_PRICE");
    }

    /**
     * Статуса инструмента отдельным потоком у Poloniex нет.
     * Состояние пары читается запросом справочника в {@code getTradingStatus}.
     */
    @Override
    public void subscribeTradingStatus(Set<InstrumentId> instruments, Consumer<TradingStatusEvent> handler) {
        // Намеренно пусто.
    }

    @Override
    public void unsubscribeAll() {
        closeSockets();
    }

    @Override
    public void onReconnect(Runnable handler) {
        if (handler != null) {
            reconnectHandlers.add(handler);
        }
    }

    @Override
    public StreamHealth health() {
        return health.snapshot();
    }

    @Override
    public void close() {
        stopping = true;
        closeSockets();
        reconnectHandlers.clear();
        ScheduledExecutorService pool = retries;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private void closeSockets() {
        for (Subscription subscription : subscriptions) {
            subscription.cancelled = true;
            closeQuietly(subscription.socket);
        }
        subscriptions.clear();
        health.markClosed();
    }

    private void closeQuietly(WebSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            client.close(socket);
        } catch (Exception e) {
            log.debug("Не удалось закрыть вебсокет Poloniex: {}", e.getMessage());
        }
    }

    /** Подписка, которая помнит себя: без этого её нечем восстановить после обрыва. */
    private static final class Subscription {
        private final List<String> symbols;
        private final Consumer<LastPrice> handler;
        private final AtomicBoolean reconnecting = new AtomicBoolean();
        private volatile WebSocket socket;
        /** Подписку сняли: восстанавливать её больше не нужно. */
        private volatile boolean cancelled;
        /** Номер попытки для нарастающей паузы. Пишется только из потока повторов. */
        private volatile int attempt;

        private Subscription(List<String> symbols, Consumer<LastPrice> handler) {
            this.symbols = List.copyOf(symbols);
            this.handler = handler;
        }
    }

    private static LastPrice toLastPrice(TickerEvent ticker) {
        if (ticker == null || ticker.getSymbol() == null || ticker.getClose() == null) {
            return null;
        }
        try {
            InstrumentId id = new InstrumentId(PoloniexSymbols.uidOf(ticker.getSymbol()), null);
            Instant ts = ticker.getTs() == null ? Instant.now() : Instant.ofEpochMilli(ticker.getTs());
            // Валюту котировки здесь не подставляем: она есть в справочнике, а тащить
            // его в горячий путь стрима ради подписи к числу — плохой размен.
            return new LastPrice(id, new Price(new BigDecimal(ticker.getClose()), null), ts);
        } catch (NumberFormatException e) {
            log.debug("Не разобрал цену из тикера {}: {}", ticker.getSymbol(), ticker.getClose());
            return null;
        }
    }

    private static List<String> symbols(Set<InstrumentId> instruments) {
        List<String> symbols = new ArrayList<>();
        if (instruments == null) {
            return symbols;
        }
        for (InstrumentId id : instruments) {
            symbols.add(PoloniexSymbols.symbolOf(id));
        }
        return symbols;
    }

    private static void runQuietly(Runnable handler) {
        try {
            handler.run();
        } catch (Exception e) {
            log.warn("Обработчик переподключения упал: {}", e.getMessage(), e);
        }
    }
}
