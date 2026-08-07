package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.Order;
import com.poloniex.api.client.spot.model.response.spot.Trade;
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
 *
 * Размер удержания берётся из СДЕЛОК заявки: в ответе на запрос заявки его нет
 * и вывести его оттуда невозможно. Пока сделки не получены, отдаётся брутто с
 * пометкой «комиссия не подтверждена» — сверка вернётся к такой записи снова.
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
                order.getUpdateTime() == null ? null : Instant.ofEpochMilli(order.getUpdateTime()),
                // Журналу нужно знать, что меньшее количество — окончательный расчёт,
                // а не запоздалое чтение: иначе он оставит прежнее, большее.
                fee.netOfFee());
    }

    /**
     * Комиссия ордера и фактически полученное количество — по СДЕЛКАМ заявки.
     *
     * Ответ на запрос заявки комиссии не содержит, и вывести её оттуда нельзя:
     * {@code filledAmount} — это сумма сделки в валюте котировки, из которой комиссия
     * НЕ вычтена, а {@code filledQuantity} — купленное количество ДО удержания. Их
     * отношение всегда даёт ровно {@code filledQuantity}, поэтому попытка получить
     * удержание как «исполнено минус зачислено» тихо возвращала брутто. Журнал
     * записывал монеты, которых на счёте нет, встречную продажу биржа отбивала
     * ошибкой 21721 «available insufficient», а сверка вставала на расхождении,
     * равном комиссии.
     *
     * Поэтому величину удержания спрашиваем там, где она есть, — в сделках.
     *
     * <h3>Почему количество не округляется</h3>
     * Зачисляется ровно {@code filledQuantity − комиссия}, с полной точностью, а шаг
     * количества — ограничение на ЗАЯВКУ, не на баланс. У DOGE_USDT шаг 0.001 при
     * комиссии с шестью знаками: округли мы нетто до шага, журнал разошёлся бы
     * с биржей на остаток, а сверка не терпит расхождения даже в один знак.
     */
    private Fee feeOf(Order order, String symbol, OrderSide side,
                      BigDecimal filled, BigDecimal avgPrice) {
        if (filled.signum() <= 0) {
            return new Fee(BigDecimal.ZERO, null, false);
        }

        PoloniexSymbols.Limits limits = symbols.limits(symbol);
        String base = limits.base() == null ? null : limits.base().toUpperCase(Locale.ROOT);

        List<Trade> trades = tradesOf(order);
        if (trades == null || trades.isEmpty()) {
            // Сделок пока не видно. Отдаём БРУТТО и НЕ выдаём догадку за факт:
            // запись останется неурегулированной, сверка вернётся к ней снова.
            return new Fee(filled, null, false);
        }

        BigDecimal tradedQuantity = BigDecimal.ZERO;
        BigDecimal feeInBase = BigDecimal.ZERO;
        BigDecimal feeInQuote = BigDecimal.ZERO;

        for (Trade trade : trades) {
            tradedQuantity = tradedQuantity.add(nvl(decimal(trade.getQuantity()), BigDecimal.ZERO));
            BigDecimal fee = decimal(trade.getFeeAmount());
            if (fee == null || fee.signum() <= 0) {
                continue;
            }
            if (base != null && base.equalsIgnoreCase(trade.getFeeCurrency())) {
                feeInBase = feeInBase.add(fee);
            } else {
                feeInQuote = feeInQuote.add(fee);
            }
        }

        // Список сделок должен покрывать весь исполненный объём. Если он отстаёт,
        // удержание окажется занижённым, а занижённое удержание — это снова позиция
        // больше фактической. Такой ответ лучше не принимать вовсе.
        if (tradedQuantity.compareTo(filled) != 0) {
            log.debug("Сделки заявки {} покрывают {} из {} — комиссию пока не фиксирую",
                    order.getId(), tradedQuantity.toPlainString(), filled.toPlainString());
            return new Fee(filled, null, false);
        }

        // Комиссия базовой монетой уменьшает то, чем бот владеет; комиссия деньгами
        // котировки количество не трогает. На продаже удержание всегда денежное.
        BigDecimal netQuantity = side == OrderSide.SELL ? filled : filled.subtract(feeInBase);

        BigDecimal bookFee = feeInQuote;
        String feeCurrency = limits.quote();
        if (feeInBase.signum() > 0) {
            // Валютой комиссии остаётся базовая монета — так в журнале видно, что
            // число получено пересчётом, а не пришло от биржи деньгами.
            bookFee = bookFee.add(avgPrice == null || avgPrice.signum() <= 0
                    ? feeInBase
                    : feeInBase.multiply(avgPrice));
            feeCurrency = base;
        }

        return new Fee(netQuantity,
                OrderFee.actual(bookFee, feeCurrency, CommissionSource.EXCHANGE_EXECUTED),
                feeInBase.signum() > 0);
    }

    /**
     * Сделки заявки, или null — если спросить не удалось.
     *
     * Пустой список и отказ различаются намеренно: пустой список — это ответ биржи,
     * а отказ означает, что комиссия по-прежнему неизвестна. Ронять из-за него разбор
     * состояния заявки нельзя: без состояния сверка не сможет даже выяснить её судьбу.
     */
    private List<Trade> tradesOf(Order order) {
        if (order.getId() == null || order.getId().isBlank()) {
            return null;
        }
        try {
            return rest.call("сделки заявки " + order.getId(), rest.api().tradesByOrder(order.getId()));
        } catch (Exception e) {
            log.debug("Не удалось получить сделки заявки {}: {}", order.getId(), e.getMessage());
            return null;
        }
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

    /**
     * Что реально получено, во что обошлось и уменьшено ли количество комиссией.
     *
     * Третий признак несёт журнал заявок: только по нему видно, что меньшее количество —
     * окончательный расчёт, а не запоздалое чтение.
     */
    private record Fee(BigDecimal netQuantity, OrderFee orderFee, boolean netOfFee) {
    }
}
