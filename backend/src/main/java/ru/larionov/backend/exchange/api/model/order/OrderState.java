package ru.larionov.backend.exchange.api.model.order;

import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.ClientOrderId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.id.OrderId;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderState(
        OrderId orderId,
        ClientOrderId clientOrderId,
        AccountId accountId,
        InstrumentId instrumentId,
        OrderSide side,

        BigDecimal requestedQuantity,
        BigDecimal executedQuantity,

        BigDecimal limitPrice,
        BigDecimal averageExecutedPrice,
        OrderFee fee,

        OrderStatus status,
        Instant createdAt,
        Instant updatedAt,

        /**
         * {@code executedQuantity} уже уменьшено на комиссию, удержанную В САМОЙ МОНЕТЕ.
         *
         * Признак нужен ровно одному потребителю — журналу заявок, который иначе держит
         * количество монотонно растущим. Монотонность защищает от запоздалого чтения,
         * занижающего исполнение, но там, где биржа берёт комиссию из получаемой валюты,
         * окончательный расчёт МЕНЬШЕ исполненного объёма — и меньшее число здесь верное.
         *
         * Выводить это из «комиссия подтверждена» нельзя: у T-Invest комиссия подтверждена
         * уже при частичном исполнении, а количество она не трогает — рублёвая комиссия
         * лотов не отнимает. Поэтому биржа, которая удержала монету, говорит об этом прямо.
         */
        boolean quantityNetOfFee
) {

    /** Прежняя форма: биржа комиссию из количества не удерживает. */
    public OrderState(OrderId orderId, ClientOrderId clientOrderId, AccountId accountId,
                      InstrumentId instrumentId, OrderSide side,
                      BigDecimal requestedQuantity, BigDecimal executedQuantity,
                      BigDecimal limitPrice, BigDecimal averageExecutedPrice, OrderFee fee,
                      OrderStatus status, Instant createdAt, Instant updatedAt) {
        this(orderId, clientOrderId, accountId, instrumentId, side,
                requestedQuantity, executedQuantity, limitPrice, averageExecutedPrice, fee,
                status, createdAt, updatedAt, false);
    }
}
