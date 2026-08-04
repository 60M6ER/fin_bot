package ru.larionov.backend.execution;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.*;
import ru.larionov.backend.exchange.api.model.account.AccountInfo;
import ru.larionov.backend.exchange.api.model.account.AccountState;
import ru.larionov.backend.exchange.api.model.account.Position;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/** Клиент-заглушка: реализованы только те группы API, которые нужны гейтвею. */
public class FakeExchangeClient implements ExchangeClient {

    private final FakeOrdersApi orders;

    /** Позиция, которую «видит» биржа. Позволяет проверить обнаружение расхождений. */
    public BigDecimal exchangePosition = BigDecimal.ZERO;
    public FeeInfo feeInfo = new FeeInfo(BigDecimal.ZERO, BigDecimal.ZERO);

    public FakeExchangeClient(FakeOrdersApi orders) {
        this.orders = orders;
    }

    @Override
    public OrdersApi orders() {
        return orders;
    }

    @Override
    public AccountsApi accounts() {
        return new AccountsApi() {
            @Override
            public List<AccountInfo> listAccounts() {
                return List.of();
            }

            @Override
            public AccountState getState(AccountId accountId) {
                return new AccountState(accountId, List.of(), List.of(), null);
            }

            @Override
            public Optional<Position> getPosition(AccountId accountId, InstrumentId instrumentId) {
                return Optional.of(new Position(instrumentId, exchangePosition, null, null, null));
            }
        };
    }

    @Override
    public InstrumentsApi instruments() {
        throw new UnsupportedOperationException("не нужен в этих тестах");
    }

    @Override
    public MarketDataApi marketData() {
        throw new UnsupportedOperationException("не нужен в этих тестах");
    }

    @Override
    public TradingCalendarApi calendar() {
        throw new UnsupportedOperationException("не нужен в этих тестах");
    }

    @Override
    public FeesApi fees() {
        return (accountId, instrumentId) -> feeInfo;
    }

    @Override
    public Optional<MarketDataStreamService> marketDataStream() {
        return Optional.empty();
    }

    @Override
    public Optional<OperationsStreamService> operationsStream() {
        return Optional.empty();
    }

    @Override
    public Optional<OrdersStreamService> ordersStream() {
        return Optional.empty();
    }

    @Override
    public ExchangeMeta meta() {
        return new ExchangeMeta(ExchangeType.T_INVEST, true, true, true, false, true);
    }
}
