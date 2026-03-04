package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.util.UUID;

public record GridConfig(
        UUID exchangeConnectionId,     // пока 1 подключение для GRID
        String instrument,             // тикер/figi/uid — как вы у себя в ExchangeClient договорились
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        int levels,
        BigDecimal orderSize,
        int maxActiveOrders,
        boolean enabled
) {
    public GridConfig {
        if (levels <= 0) throw new IllegalArgumentException("levels must be > 0");
        if (maxActiveOrders <= 0) throw new IllegalArgumentException("maxActiveOrders must be > 0");
    }
}
