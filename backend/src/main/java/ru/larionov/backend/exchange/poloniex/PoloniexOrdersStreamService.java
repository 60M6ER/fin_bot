package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.event.spot.OrderEvent;
import com.poloniex.api.client.spot.model.event.spot.PoloEvent;
import com.poloniex.api.client.spot.ws.PoloApiCallback;
import com.poloniex.api.client.spot.ws.spot.SpotPoloPrivateWebsocketClient;
import lombok.extern.slf4j.Slf4j;
import okhttp3.WebSocket;
import ru.larionov.backend.exchange.api.OrdersStreamService;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.CommissionSource;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.exchange.common.StreamHealthTracker;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Приватный поток событий по заявкам Poloniex.
 *
 * Быстрый путь узнать об исполнении: событие приходит с нашим {@code clientOrderId},
 * поэтому сопоставляется с журналом напрямую и опрашивать статусы не нужно.
 *
 * <h3>Чем этот поток ценнее REST-ответа</h3>
 * Событие несёт {@code tradeFee} и {@code feeCurrency} — ФАКТИЧЕСКУЮ комиссию,
 * которой в ответе на запрос ордера нет. Для Poloniex это принципиально: комиссия
 * покупки берётся в базовой монете, и без её точной величины журнал не знает,
 * сколько монет реально зачислено.
 *
 * <h3>Чего он не отменяет</h3>
 * Сверку. За время разрыва события теряются безвозвратно, поэтому после
 * переподключения состояние восстанавливается только REST-запросом.
 */
@Slf4j
public class PoloniexOrdersStreamService implements OrdersStreamService {

    private final SpotPoloPrivateWebsocketClient client;
    private final PoloniexSymbols symbols;
    private final StreamHealthTracker health = new StreamHealthTracker();
    private final List<WebSocket> sockets = new CopyOnWriteArrayList<>();
    private final List<Runnable> reconnectHandlers = new CopyOnWriteArrayList<>();

    public PoloniexOrdersStreamService(String wsUrl, String apiKey, String secret, PoloniexSymbols symbols) {
        this.client = new SpotPoloPrivateWebsocketClient(wsUrl, apiKey, secret);
        this.symbols = symbols;
    }

    @Override
    public void subscribeOrderStates(AccountId accountId, Consumer<OrderState> handler) {
        // "all" — все символы счёта: бот фильтрует свои заявки по clientOrderId,
        // а подписываться на каждый символ отдельно означало бы держать по сокету
        // на бота при общем лимите соединений.
        sockets.add(client.onOrderEvent(List.of("all"), new PoloApiCallback<OrderEvent>() {
            @Override
            public void onResponse(PoloEvent<OrderEvent> event) {
                health.markEvent();
                if (event == null || event.getData() == null) {
                    return;
                }
                for (OrderEvent order : event.getData()) {
                    OrderState state = toState(order, accountId);
                    if (state != null) {
                        handler.accept(state);
                    }
                }
            }

            @Override
            public void onFailure(Throwable t) {
                log.warn("Ордерный стрим Poloniex оборвался: {}", t.getMessage());
                health.markError(t.getMessage());
                reconnectHandlers.forEach(PoloniexOrdersStreamService::runQuietly);
            }
        }));
        health.markConnected();
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
        for (WebSocket socket : sockets) {
            try {
                client.close(socket);
            } catch (Exception e) {
                log.debug("Не удалось закрыть ордерный вебсокет: {}", e.getMessage());
            }
        }
        sockets.clear();
        reconnectHandlers.clear();
        health.markClosed();
    }

    /**
     * Событие биржи в наш контракт.
     *
     * Заявки без нашего clientOrderId пропускаем: это ручная сделка в приложении
     * или другой бот на том же ключе. Штатная ситуация, а не ошибка.
     */
    private OrderState toState(OrderEvent event, AccountId accountId) {
        if (event == null || event.getClientOrderId() == null || event.getClientOrderId().isBlank()) {
            return null;
        }

        String symbol = event.getSymbol();
        OrderSide side = "SELL".equalsIgnoreCase(event.getSide()) ? OrderSide.SELL : OrderSide.BUY;
        BigDecimal requested = decimal(event.getQuantity());
        BigDecimal filled = decimal(event.getFilledQuantity());
        BigDecimal price = decimal(event.getPrice());
        BigDecimal tradePrice = decimal(event.getTradePrice());
        BigDecimal avgPrice = tradePrice != null && tradePrice.signum() > 0 ? tradePrice : price;

        BigDecimal feeAmount = decimal(event.getTradeFee());
        String feeCurrency = event.getFeeCurrency();
        BigDecimal netFilled = filled;
        OrderFee fee = null;

        if (feeAmount != null && feeAmount.signum() > 0 && feeCurrency != null) {
            String base = baseOf(symbol);
            if (base != null && base.equalsIgnoreCase(feeCurrency)) {
                // Комиссия удержана монетой: зачислено на столько же меньше.
                // Не вычесть её здесь — значит записать в журнал позицию, которой нет.
                netFilled = filled == null ? null : filled.subtract(feeAmount);
                netFilled = quantizeDown(netFilled, symbol);
                fee = OrderFee.actual(
                        avgPrice == null ? feeAmount : feeAmount.multiply(avgPrice),
                        feeCurrency, CommissionSource.EXCHANGE_EXECUTED);
            } else {
                // Комиссия деньгами котировки — записываем как есть.
                fee = OrderFee.actual(feeAmount, feeCurrency, CommissionSource.EXCHANGE_EXECUTED);
            }
        }

        return new OrderState(
                new OrderId(event.getOrderId()),
                new ClientOrderId(event.getClientOrderId()),
                accountId,
                new InstrumentId(PoloniexSymbols.uidOf(symbol), null),
                side,
                requested,
                netFilled,
                price,
                avgPrice,
                fee,
                status(event.getState()),
                event.getCreateTime() == null ? null : Instant.ofEpochMilli(event.getCreateTime()),
                event.getTs() == null ? null : Instant.ofEpochMilli(event.getTs()));
    }

    private String baseOf(String symbol) {
        if (symbol == null) {
            return null;
        }
        try {
            return symbols.limits(symbol).base();
        } catch (Exception e) {
            log.debug("Неизвестный символ в событии заявки: {}", symbol);
            return null;
        }
    }

    private BigDecimal quantizeDown(BigDecimal quantity, String symbol) {
        if (quantity == null || quantity.signum() <= 0 || symbol == null) {
            return quantity;
        }
        try {
            BigDecimal step = symbols.limits(symbol).quantityStep();
            return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
        } catch (Exception e) {
            return quantity;
        }
    }

    private static OrderStatus status(String state) {
        if (state == null) {
            return OrderStatus.UNKNOWN;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "NEW", "PENDING_NEW" -> OrderStatus.NEW;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED", "PARTIALLY_CANCELED" -> OrderStatus.CANCELLED;
            case "REJECTED", "FAILED" -> OrderStatus.REJECTED;
            default -> OrderStatus.UNKNOWN;
        };
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

    private static void runQuietly(Runnable handler) {
        try {
            handler.run();
        } catch (Exception e) {
            log.warn("Обработчик переподключения упал: {}", e.getMessage(), e);
        }
    }
}
