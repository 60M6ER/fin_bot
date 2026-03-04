package ru.larionov.backend.exchange.tinvest;

import io.github.resilience4j.retry.RetryConfig;
import ru.larionov.backend.exchange.api.*;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;
import ru.larionov.backend.enums.ExchangeType;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.resilience.ResilienceConfiguration;
import ru.ttech.piapi.core.connector.resilience.ResilienceSyncStubWrapper;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;

/**
 * T-Invest client.
 *
 * Внутри держит low-level SDK (gRPC) + resilience wrappers, а наружу отдаёт наш доменный контракт {@link ExchangeClient}
 * через набор API-групп (InstrumentsApi/OrdersApi/... ).
 */
public final class TInvestExchangeClient implements ExchangeClient {

    private final ConnectorConfiguration connectorConfiguration;
    private final ScheduledExecutorService resilienceExecutor;
    private final ServiceStubFactory unaryServiceFactory;

    // Resilience wrappers for sync unary services (low-level)
    private final ResilienceSyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsStub;
    private final ResilienceSyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketDataStub;
    private final ResilienceSyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> ordersStub;
    private final ResilienceSyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operationsStub;
    private final ResilienceSyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> usersStub;

    // Our domain APIs (high-level)
    private final InstrumentsApi instrumentsApi;
    private final MarketDataApi marketDataApi;
    private final OrdersApi ordersApi;
    private final TradingCalendarApi tradingCalendarApi;
    private final AccountsApi accountsApi;
    private final FeesApi feesApi;

    private final ExchangeMeta meta;

    private static ExchangeMeta defaultMeta() {
        return new ExchangeMeta(
                ExchangeType.T_INVEST,
                true,  // supportsTradingCalendar
                false, // supportsMarketDataStream (not implemented yet)
                false, // supportsOrderEventsStream (not implemented yet)
                false, // supportsFutures (not used in MVP)
                true   // supportsSandbox
        );
    }

    private TInvestExchangeClient(
            ConnectorConfiguration connectorConfiguration,
            ScheduledExecutorService resilienceExecutor,
            ServiceStubFactory unaryServiceFactory,
            ResilienceSyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsStub,
            ResilienceSyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketDataStub,
            ResilienceSyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> ordersStub,
            ResilienceSyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operationsStub,
            ResilienceSyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> usersStub,
            ExchangeMeta meta
    ) {
        this.connectorConfiguration = connectorConfiguration;
        this.resilienceExecutor = resilienceExecutor;
        this.unaryServiceFactory = unaryServiceFactory;

        this.instrumentsStub = instrumentsStub;
        this.marketDataStub = marketDataStub;
        this.ordersStub = ordersStub;
        this.operationsStub = operationsStub;
        this.usersStub = usersStub;

        this.meta = meta;

        // Bind high-level APIs (implementation classes already exist in this package)
        this.instrumentsApi = new TInvestInstrumentsApi(this);
        this.marketDataApi = new TInvestMarketDataApi(this);
        this.ordersApi = new TInvestOrdersApi(this);
        this.tradingCalendarApi = new TInvestTradingCalendarApi(this);
        this.accountsApi = new TInvestAccountsApi(this);
        this.feesApi = new TInvestFeesApi(this);
    }

    /**
     * Factory with explicit {@link ExchangeMeta}. Preferred.
     */
    public static TInvestExchangeClient create(
            ConnectorConfiguration connectorConfiguration,
            ScheduledExecutorService resilienceExecutor
    ) {
        Objects.requireNonNull(connectorConfiguration, "connectorConfiguration");
        Objects.requireNonNull(resilienceExecutor, "resilienceExecutor");

        ServiceStubFactory factory = ServiceStubFactory.create(connectorConfiguration);

        // Base retry for unary calls. Later we can override per service/method.
        RetryConfig retry = RetryConfig.custom()
                .waitDuration(Duration.ofMillis(3000))
                .maxAttempts(5)
                .build();

        ResilienceConfiguration rc = ResilienceConfiguration.builder(resilienceExecutor, connectorConfiguration)
                .withDefaultRetry(retry)
                .build();

        ResilienceSyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instruments =
                factory.newResilienceSyncService(InstrumentsServiceGrpc::newBlockingStub, rc);

        ResilienceSyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketData =
                factory.newResilienceSyncService(MarketDataServiceGrpc::newBlockingStub, rc);

        ResilienceSyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> orders =
                factory.newResilienceSyncService(OrdersServiceGrpc::newBlockingStub, rc);

        ResilienceSyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operations =
                factory.newResilienceSyncService(OperationsServiceGrpc::newBlockingStub, rc);

        ResilienceSyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> users =
                factory.newResilienceSyncService(UsersServiceGrpc::newBlockingStub, rc);

        return new TInvestExchangeClient(
                connectorConfiguration,
                resilienceExecutor,
                factory,
                instruments,
                marketData,
                orders,
                operations,
                users,
                defaultMeta()
        );
    }

    // ===== Low-level access for package implementations (TInvest*Api) =====

    public ConnectorConfiguration connectorConfiguration() {
        return connectorConfiguration;
    }

    public ScheduledExecutorService resilienceExecutor() {
        return resilienceExecutor;
    }

    public ServiceStubFactory unaryServiceFactory() {
        return unaryServiceFactory;
    }

    public ResilienceSyncStubWrapper<InstrumentsServiceGrpc.InstrumentsServiceBlockingStub> instrumentsStub() {
        return instrumentsStub;
    }

    public ResilienceSyncStubWrapper<MarketDataServiceGrpc.MarketDataServiceBlockingStub> marketDataStub() {
        return marketDataStub;
    }

    public ResilienceSyncStubWrapper<OrdersServiceGrpc.OrdersServiceBlockingStub> ordersStub() {
        return ordersStub;
    }

    public ResilienceSyncStubWrapper<OperationsServiceGrpc.OperationsServiceBlockingStub> operationsStub() {
        return operationsStub;
    }

    public ResilienceSyncStubWrapper<UsersServiceGrpc.UsersServiceBlockingStub> usersStub() {
        return usersStub;
    }

    // ===== ExchangeClient (domain contract) =====

    @Override
    public InstrumentsApi instruments() {
        return instrumentsApi;
    }

    @Override
    public MarketDataApi marketData() {
        return marketDataApi;
    }

    @Override
    public OrdersApi orders() {
        return ordersApi;
    }

    @Override
    public TradingCalendarApi calendar() {
        return tradingCalendarApi;
    }

    @Override
    public AccountsApi accounts() {
        return accountsApi;
    }

    @Override
    public FeesApi fees() {
        return feesApi;
    }

    @Override
    public ExchangeMeta meta() {
        return meta;
    }

    @Override
    public java.util.Optional<MarketDataStreamService> marketDataStream() {
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OperationsStreamService> operationsStream() {
        return java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<OrdersStreamService> ordersStream() {
        return java.util.Optional.empty();
    }
}
