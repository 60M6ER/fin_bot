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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Публичный поток рыночных данных Poloniex.
 *
 * Подписка на тикер, а не на стакан: сетке достаточно последней цены, а событий
 * по стакану на порядок больше — на криптобирже это заметная разница в нагрузке
 * на очередь событий бота.
 *
 * <h3>Про переподключение</h3>
 * За время разрыва события теряются безвозвратно, поэтому реконнект обязан приводить
 * к REST-сверке — иначе бот продолжит действовать по состоянию, которое биржа уже
 * не подтверждает. Хук {@link #onReconnect} для этого и существует; вызывающая
 * сторона (StrategyBotHandler) вешает на него сверку.
 */
@Slf4j
public class PoloniexMarketDataStreamService implements MarketDataStreamService {

    private final SpotPoloPublicWebsocketClient client;
    private final StreamHealthTracker health = new StreamHealthTracker();
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();

    public PoloniexMarketDataStreamService(String wsUrl) {
        this.client = new SpotPoloPublicWebsocketClient(wsUrl);
    }

    @Override
    public void subscribeLastPrice(Set<InstrumentId> instruments, Consumer<LastPrice> handler) {
        List<String> symbols = symbols(instruments);
        if (symbols.isEmpty()) {
            return;
        }

        sockets.add(client.onTickerEvent(symbols, new PoloApiCallback<TickerEvent>() {
            @Override
            public void onResponse(PoloEvent<TickerEvent> event) {
                health.markEvent();
                if (event == null || event.getData() == null) {
                    return;
                }
                for (TickerEvent ticker : event.getData()) {
                    LastPrice price = toLastPrice(ticker);
                    if (price != null) {
                        handler.accept(price);
                    }
                }
            }

            @Override
            public void onFailure(Throwable t) {
                // Разрыв — не исключительная ситуация, а обычная жизнь вебсокета.
                // Важно не проглотить его молча: после переподключения нужна сверка.
                log.warn("Стрим рыночных данных Poloniex оборвался: {}", t.getMessage());
                health.markError(t.getMessage());
                reconnectHandlers.forEach(PoloniexMarketDataStreamService::runQuietly);
            }
        }));
        health.markConnected();
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
        closeSockets();
        reconnectHandlers.clear();
    }

    private void closeSockets() {
        for (WebSocket socket : sockets) {
            try {
                client.close(socket);
            } catch (Exception e) {
                log.debug("Не удалось закрыть вебсокет Poloniex: {}", e.getMessage());
            }
        }
        sockets.clear();
        health.markClosed();
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
