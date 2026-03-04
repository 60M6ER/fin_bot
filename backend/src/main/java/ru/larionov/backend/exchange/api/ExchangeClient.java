package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;

import java.util.Optional;

public interface ExchangeClient {
    InstrumentsApi instruments();
    MarketDataApi marketData();
    OrdersApi orders();

    TradingCalendarApi calendar();
    AccountsApi accounts();
    FeesApi fees();

    // Стримы отдельно, потому что не у всех будут (или будут по-разному)
    Optional<MarketDataStreamService> marketDataStream();
    Optional<OperationsStreamService> operationsStream();
    Optional<OrdersStreamService> ordersStream();

    ExchangeMeta meta(); // что поддерживается, лимиты, особенности

    default void close() {
        // no-op by default
    }
}
