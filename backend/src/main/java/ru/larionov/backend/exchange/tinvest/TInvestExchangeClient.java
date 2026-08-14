package ru.larionov.backend.exchange.tinvest;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import ru.larionov.backend.exchange.api.*;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;
import ru.larionov.backend.enums.ExchangeType;
import ru.tinkoff.piapi.contract.v1.*;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;
import ru.ttech.piapi.core.connector.ServiceStubFactory;
import ru.ttech.piapi.core.connector.resilience.ResilienceConfiguration;
import ru.ttech.piapi.core.connector.resilience.ResilienceSyncStubWrapper;
import ru.ttech.piapi.core.connector.streaming.StreamServiceStubFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final boolean sandbox;
    /** Ставка комиссии за одну сторону сделки, из настроек подключения. */
    private final java.math.BigDecimal commissionRate;

    // Стримы поднимаются лениво и живут до close().
    private final Object streamLock = new Object();
    private volatile StreamServiceStubFactory streamStubFactory;
    private volatile ExecutorService streamCallbackExecutor;
    private volatile TInvestMarketDataStreamService marketDataStreamService;
    private volatile TInvestOrdersStreamService ordersStreamService;

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
    private final MarginApi marginApi;

    private final ExchangeMeta meta;

    private static ExchangeMeta defaultMeta() {
        return new ExchangeMeta(
                ExchangeType.T_INVEST,
                true,  // supportsTradingCalendar
                true,  // supportsMarketDataStream
                true,  // supportsOrderEventsStream
                true,  // supportsFutures — справочник выгружает срочный рынок
                true,  // supportsSandbox
                // Брокерский счёт ведётся в валютах, а не в монетах: рубль основной,
                // остальные встречаются на счетах с валютными инструментами.
                List.of("RUB", "USD", "EUR", "CNY", "HKD"),
                // Тариф API дёшево не отдаёт — ставку задаёт пользователь в настройках.
                false
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
            ExchangeMeta meta,
            boolean sandbox,
            java.math.BigDecimal commissionRate
    ) {
        this.connectorConfiguration = connectorConfiguration;
        this.resilienceExecutor = resilienceExecutor;
        this.unaryServiceFactory = unaryServiceFactory;
        this.sandbox = sandbox;
        this.commissionRate = commissionRate;

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
        this.marginApi = new TInvestMarginApi(this);
    }

    public static TInvestExchangeClient create(
            ConnectorConfiguration connectorConfiguration,
            ScheduledExecutorService resilienceExecutor,
            boolean sandbox
    ) {
        return create(connectorConfiguration, resilienceExecutor, sandbox,
                ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings.DEFAULT_COMMISSION_RATE);
    }

    public static TInvestExchangeClient create(
            ConnectorConfiguration connectorConfiguration,
            ScheduledExecutorService resilienceExecutor,
            boolean sandbox,
            java.math.BigDecimal commissionRate
    ) {
        Objects.requireNonNull(connectorConfiguration, "connectorConfiguration");
        Objects.requireNonNull(resilienceExecutor, "resilienceExecutor");

        ServiceStubFactory factory = ServiceStubFactory.create(connectorConfiguration);

        /*
         * Повторяем только то, что имеет шанс пройти со второй попытки.
         *
         * Предикат здесь обязателен. RetryConfig.custom() без retryOnException повторяет
         * ЛЮБОЕ исключение, и именно это превратило штатный ответ «такого ордера нет»
         * в отказ всей торговли: каждый запрос о несуществующем ордере стоил
         * 5 попыток × 3 с = 12 секунд единственного потока бота, а его пять отказов
         * подряд копились в circuit breaker, пока тот не размыкал цепь на GetOrderState
         * целиком. Ретрай не должен спорить с биржей о фактах.
         */
        RetryConfig retry = RetryConfig.custom()
                .waitDuration(Duration.ofMillis(3000))
                .maxAttempts(5)
                .retryOnException(TInvestErrors::isRetryable)
                .build();

        /*
         * Circuit breaker размыкается на поломках сервиса, а не на ответах по существу.
         * NOT_FOUND, INVALID_ARGUMENT и отказ риск-контроля брокера — это ответы:
         * они не значат, что метод недоступен, и не должны отнимать его у остальных.
         */
        CircuitBreakerConfig circuitBreaker = CircuitBreakerConfig.custom()
                .ignoreException(t -> !TInvestErrors.isServiceFailure(t))
                .build();

        ResilienceConfiguration rc = ResilienceConfiguration.builder(resilienceExecutor, connectorConfiguration)
                .withDefaultRetry(retry)
                .withDefaultCircuitBreaker(circuitBreaker)
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
                defaultMeta(),
                sandbox,
                commissionRate
        );
    }

    /** Режим песочницы. Раньше это пытались выяснить рефлексией и всегда получали false. */
    public boolean isSandbox() {
        return sandbox;
    }

    public java.math.BigDecimal commissionRate() {
        return commissionRate;
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
    public Optional<MarginApi> margin() {
        return Optional.of(marginApi);
    }

    @Override
    public ExchangeMeta meta() {
        return meta;
    }

    /**
     * Стримы создаются лениво: боту без подписок они не нужны, а каждый —
     * это живое gRPC-соединение и потоки.
     */
    @Override
    public java.util.Optional<MarketDataStreamService> marketDataStream() {
        TInvestMarketDataStreamService local = marketDataStreamService;
        if (local == null) {
            synchronized (streamLock) {
                local = marketDataStreamService;
                if (local == null) {
                    local = new TInvestMarketDataStreamService(
                            streamServiceStubFactory(), streamCallbackExecutor(), resilienceExecutor);
                    marketDataStreamService = local;
                }
            }
        }
        return java.util.Optional.of(local);
    }

    @Override
    public java.util.Optional<OrdersStreamService> ordersStream() {
        TInvestOrdersStreamService local = ordersStreamService;
        if (local == null) {
            synchronized (streamLock) {
                local = ordersStreamService;
                if (local == null) {
                    local = new TInvestOrdersStreamService(streamServiceStubFactory(), resilienceExecutor);
                    ordersStreamService = local;
                }
            }
        }
        return java.util.Optional.of(local);
    }

    /**
     * Стрим операций вне объёма: позиции даёт ордерный стрим плюс сверка,
     * а отдельное соединение добавило бы сложность без выигрыша.
     */
    @Override
    public java.util.Optional<OperationsStreamService> operationsStream() {
        return java.util.Optional.empty();
    }

    /**
     * Здоровье стримов без побочного эффекта: если стрим ещё не поднимали, отдаём
     * «отключено», а не создаём соединение. Иначе опрос статуса из UI сам бы
     * открывал стримы, которые никто не заказывал.
     */
    public ru.larionov.backend.exchange.api.model.stream.StreamHealth marketDataStreamHealth() {
        TInvestMarketDataStreamService local = marketDataStreamService;
        return local == null
                ? ru.larionov.backend.exchange.api.model.stream.StreamHealth.disconnected()
                : local.health();
    }

    public ru.larionov.backend.exchange.api.model.stream.StreamHealth ordersStreamHealth() {
        TInvestOrdersStreamService local = ordersStreamService;
        return local == null
                ? ru.larionov.backend.exchange.api.model.stream.StreamHealth.disconnected()
                : local.health();
    }

    private StreamServiceStubFactory streamServiceStubFactory() {
        StreamServiceStubFactory local = streamStubFactory;
        if (local == null) {
            local = StreamServiceStubFactory.create(unaryServiceFactory);
            streamStubFactory = local;
        }
        return local;
    }

    /**
     * Отдельный пул под колбэки стримов. Пул должен расти: SDK запускает
     * по блокирующему циклу разбора на каждый тип подписки, и на фиксированном
     * пуле они бы заблокировали друг друга.
     */
    private ExecutorService streamCallbackExecutor() {
        ExecutorService local = streamCallbackExecutor;
        if (local == null) {
            local = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "tinvest-stream");
                t.setDaemon(true);
                return t;
            });
            streamCallbackExecutor = local;
        }
        return local;
    }

    /**
     * Раньше этот метод не переопределялся, а в ExchangeClient он default no-op — поэтому
     * stop() у хендлера закрывал клиент вхолостую, и gRPC-канал вместе с его потоками
     * утекал на каждом цикле активации/деактивации подключения.
     */
    @Override
    public void close() {
        // Порядок важен: сначала снимаем подписки и рвём стримы, потом гасим их пул,
        // и только затем канал — иначе SDK будет писать в закрытый канал.
        synchronized (streamLock) {
            closeQuietly(marketDataStreamService, "market data stream");
            marketDataStreamService = null;

            closeQuietly(ordersStreamService, "orders stream");
            ordersStreamService = null;

            streamStubFactory = null;

            ExecutorService ex = streamCallbackExecutor;
            streamCallbackExecutor = null;
            if (ex != null) {
                ex.shutdownNow();
            }
        }

        try {
            io.grpc.ManagedChannel channel = unaryServiceFactory.getChannel();
            if (channel != null && !channel.isShutdown()) {
                channel.shutdown();
                if (!channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable, String what) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TInvestExchangeClient.class)
                    .warn("Failed to close {}: {}", what, e.getMessage());
        }
    }
}
