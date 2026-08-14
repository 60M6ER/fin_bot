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

    /**
     * Маржинальные показатели счёта — если площадке есть что о них сказать.
     *
     * Пусто у спотовых бирж: непокрытых позиций там не бывает, и отвечать нечем.
     * Пустота означает «спросить не у кого», а не «маржа запрещена»: разрешение
     * даёт галка в подключении, и бот с ней, но без источника показателей обязан
     * не стартовать — торговать с плечом, не умея спросить обеспечение, нельзя.
     */
    default Optional<MarginApi> margin() {
        return Optional.empty();
    }

    // Стримы отдельно, потому что не у всех будут (или будут по-разному)
    Optional<MarketDataStreamService> marketDataStream();
    Optional<OperationsStreamService> operationsStream();
    Optional<OrdersStreamService> ordersStream();

    ExchangeMeta meta(); // что поддерживается, лимиты, особенности

    default void close() {
        // no-op by default
    }
}
