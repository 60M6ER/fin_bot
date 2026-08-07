package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.OrdersApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;
import ru.larionov.backend.exchange.api.model.order.CommissionSource;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.exchange.api.model.order.OrderRequest;
import ru.larionov.backend.exchange.api.model.order.OrderResponse;
import ru.larionov.backend.exchange.api.model.order.OrderState;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Заявки на Poloniex.
 *
 * <h3>Идемпотентность</h3>
 * {@code clientOrderId} уходит на биржу вместе с заявкой, а
 * {@link #getByClientOrderId} умеет спросить о её судьбе по нему же. Это и делает
 * возможной всю схему гейтвея: оборванный сетевой вызов не порождает дубль, потому
 * что повторная постановка с тем же идентификатором биржей отвергается, а состояние
 * всегда можно выяснить.
 *
 * <h3>Комиссия в базовой валюте</h3>
 * Poloniex берёт комиссию в ПОЛУЧАЕМОЙ валюте: при покупке BTC_USDT — в биткойне.
 * Значит монет приходит меньше, чем указано в заявке. Если этого не учесть, позиция
 * по журналу окажется больше фактической, встречные продажи начнёт отбивать биржа,
 * а сверка — бесконечно сообщать о расхождении.
 *
 * Поэтому здесь делается две вещи:
 * <ul>
 *   <li>{@code executedQuantity} уменьшается на комиссию, взятую в базовой валюте, —
 *       журнал хранит то, чем бот РЕАЛЬНО владеет;</li>
 *   <li>сама комиссия пересчитывается в валюту котировки по цене исполнения, чтобы
 *       денежная книга осталась однородной. Исходная валюта комиссии при этом
 *       сохраняется в {@link OrderFee#currency()} и видна в журнале.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class PoloniexOrdersApi implements OrdersApi {

    private final PoloniexRest rest;
    private final PoloniexSymbols symbols;

    @Override
    public OrderResponse placeLimit(OrderRequest req) {
        String symbol = PoloniexSymbols.symbolOf(req.instrumentId());
        PoloniexSymbols.Limits limits = symbols.limits(symbol);

        // Порядок полей не важен, но состав важен: без accountType биржа не поймёт,
        // с какого кошелька торговать, а без clientOrderId рухнет идемпотентность.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("side", req.side() == OrderSide.SELL ? "SELL" : "BUY");
        body.put("type", "LIMIT");
        body.put("accountType", "SPOT");
        body.put("timeInForce", "GTC");
        body.put("price", limits.formatPrice(req.limitPrice()));
        body.put("quantity", limits.formatQuantity(req.quantity()));
        body.put("clientOrderId", req.clientOrderId().value());

        Order order = rest.call("постановка заявки " + symbol, rest.api().placeOrder(body));
        OrderState state = toState(order, req.accountId(), req.instrumentId(), symbol, req);

        return new OrderResponse(state.orderId(), req.clientOrderId(), state);
    }

    @Override
    public void cancel(AccountId accountId, OrderId orderId) {
        rest.call("снятие заявки " + orderId.value(), rest.api().cancelById(orderId.value()));
    }

    @Override
    public Optional<OrderState> get(AccountId accountId, OrderId orderId) {
        return rest.callAllowingNotFound("состояние заявки " + orderId.value(),
                        rest.api().orderById(orderId.value()))
                .map(order -> toState(order, accountId, instrumentOf(order), order.getSymbol(), null));
    }

    @Override
    public Optional<OrderState> getByClientOrderId(AccountId accountId, ClientOrderId clientOrderId) {
        return rest.callAllowingNotFound("состояние заявки cid:" + clientOrderId.value(),
                        rest.api().orderByClientOrderId(clientOrderId.value()))
                .map(order -> toState(order, accountId, instrumentOf(order), order.getSymbol(), null));
    }

    @Override
    public List<OrderState> listOpen(AccountId accountId, InstrumentId instrumentId) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        List<Order> orders = rest.call("живые заявки " + symbol,
                rest.api().openOrders(symbol, null, 500));
        if (orders == null) {
            return List.of();
        }
        return orders.stream()
                .map(order -> toState(order, accountId, instrumentId, symbol, null))
                .toList();
    }

    // ------------------------------------------------------------------ отображение

    private InstrumentId instrumentOf(Order order) {
        return new InstrumentId(PoloniexSymbols.uidOf(order.getSymbol()), null);
    }

    private OrderState toState(Order order, AccountId accountId, InstrumentId instrumentId,
                               String symbol, OrderRequest fallback) {
        if (order == null) {
            throw new IllegalStateException("Poloniex не вернул заявку по " + symbol);
        }

        OrderSide side = "SELL".equalsIgnoreCase(order.getSide()) ? OrderSide.SELL : OrderSide.BUY;
        BigDecimal requested = nvl(order.getQuantity(), fallback == null ? null : fallback.quantity());
        BigDecimal filled = nvl(order.getFilledQuantity(), BigDecimal.ZERO);
        BigDecimal avgPrice = order.getAvgPrice() != null && order.getAvgPrice().signum() > 0
                ? order.getAvgPrice()
                : nvl(order.getPrice(), fallback == null ? null : fallback.limitPrice());

        Fee fee = feeOf(order, symbol, side, filled, avgPrice);

        return new OrderState(
                new OrderId(order.getId()),
                new ClientOrderId(order.getClientOrderId()),
                accountId,
                instrumentId,
                side,
                requested,
                // Именно НЕТТО: то, что действительно легло на баланс.
                fee.netQuantity(),
                nvl(order.getPrice(), fallback == null ? null : fallback.limitPrice()),
                avgPrice,
                fee.orderFee(),
                status(order.getState(), filled, requested),
                order.getCreateTime() == null ? null : Instant.ofEpochMilli(order.getCreateTime()),
                order.getUpdateTime() == null ? null : Instant.ofEpochMilli(order.getUpdateTime()));
    }

    /**
     * Комиссия ордера и фактически полученное количество.
     *
     * Ответ ордера комиссию не содержит — она приходит в сделках. Поэтому здесь
     * работает ОЦЕНКА по разнице «исполнено минус зачислено», когда биржа её даёт,
     * и по ставке, когда не даёт. Точный факт подтягивает {@link PoloniexFeesApi}
     * и сверка по сделкам — так же, как это устроено у T-Invest.
     */
    private Fee feeOf(Order order, String symbol, OrderSide side,
                      BigDecimal filled, BigDecimal avgPrice) {
        if (filled.signum() <= 0) {
            return new Fee(BigDecimal.ZERO, null);
        }

        PoloniexSymbols.Limits limits = symbols.limits(symbol);
        String base = limits.base() == null ? null : limits.base().toUpperCase(Locale.ROOT);

        // filledAmount — сколько денег котировки прошло по сделке. Для продажи из него
        // видно, сколько реально получено, но количество базовой монеты оно не меняет.
        // Комиссия базовой валютой бьёт только по ПОКУПКЕ: монет приходит меньше.
        if (side == OrderSide.SELL) {
            return new Fee(filled, null);
        }

        BigDecimal netQuantity = filled;
        BigDecimal feeInQuote = null;

        if (avgPrice != null && avgPrice.signum() > 0 && order.getFilledAmount() != null) {
            // Комиссию в базовой валюте оценить из ответа ордера нельзя; берём её
            // как долю от суммы сделки только тогда, когда биржа отдала обе величины.
            BigDecimal impliedQuantity = order.getFilledAmount().divide(avgPrice, 18, RoundingMode.DOWN);
            if (impliedQuantity.signum() > 0 && impliedQuantity.compareTo(filled) < 0) {
                BigDecimal feeInBase = filled.subtract(impliedQuantity);
                netQuantity = quantizeDown(impliedQuantity, limits.quantityStep());
                feeInQuote = feeInBase.multiply(avgPrice);
            }
        }

        if (feeInQuote == null) {
            return new Fee(quantizeDown(netQuantity, limits.quantityStep()), null);
        }

        // Валютой комиссии остаётся базовая монета — так в журнале видно, что число
        // получено пересчётом, а не пришло от биржи деньгами.
        return new Fee(netQuantity, OrderFee.actual(feeInQuote, base, CommissionSource.EXCHANGE_EXECUTED));
    }

    private static BigDecimal quantizeDown(BigDecimal quantity, BigDecimal step) {
        if (quantity == null || quantity.signum() <= 0 || step == null || step.signum() <= 0) {
            return quantity == null ? BigDecimal.ZERO : quantity;
        }
        return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    /**
     * Состояние заявки Poloniex в наши термины.
     *
     * PARTIALLY_CANCELED — частично исполнена и снята. Терминальное состояние, и
     * назвать его CANCELLED правильнее, чем PARTIALLY_FILLED: заявка мертва, ждать
     * от неё больше нечего, а исполненная часть уже в журнале.
     */
    private static OrderStatus status(String state, BigDecimal filled, BigDecimal requested) {
        if (state == null) {
            return OrderStatus.UNKNOWN;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "NEW", "PENDING_NEW" -> OrderStatus.NEW;
            case "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrderStatus.FILLED;
            case "CANCELED", "PARTIALLY_CANCELED" -> OrderStatus.CANCELLED;
            case "REJECTED", "FAILED" -> OrderStatus.REJECTED;
            default -> {
                log.debug("Неизвестное состояние заявки Poloniex: {}", state);
                yield OrderStatus.UNKNOWN;
            }
        };
    }

    private static BigDecimal nvl(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    /** Что реально получено и во что обошлось. */
    private record Fee(BigDecimal netQuantity, OrderFee orderFee) {
    }
}
