package ru.larionov.backend.exchange.tinvest;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.service.ExchangeHandler;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;

/**
 * T-Invest (Tinkoff Invest) exchange handler.
 *
 * Работает с уже разрешённым {@link ExchangeConnectionContext}: секреты расшифрованы,
 * настройки разобраны. Про JPA и шифрование адаптер ничего не знает.
 */
@Slf4j
public final class TInvestExchangeHandler implements ExchangeHandler {

    private final ExchangeConnectionContext connection;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ExchangeClient client; // created on start
    private volatile ConnectorConfiguration connectorConfiguration;
    private volatile ScheduledExecutorService resilienceExecutor;

    /** Счёт, подтверждённый health-check'ом. Им же дальше торгуют боты. */
    private volatile AccountId resolvedAccountId;

    public TInvestExchangeHandler(ExchangeConnectionContext connection) {
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
        this.connection = connection;
    }

    @Override
    public UUID connectionId() {
        return connection.id();
    }

    @Override
    public ExchangeType exchangeType() {
        return ExchangeType.T_INVEST;
    }

    public ExchangeConnectionContext context() {
        return connection;
    }

    public AccountId resolvedAccountId() {
        return resolvedAccountId;
    }

    @Override
    public void start() {
        if (started.get()) {
            return;
        }

        lifecycleLock.lock();
        try {
            if (started.get()) {
                return;
            }

            if (connection.apiKey() == null || connection.apiKey().isBlank()) {
                throw new IllegalStateException(
                        "Не задан токен T-Invest для подключения «" + connection.name() + "». "
                                + "Укажите его в разделе «Биржи» → «Ключи».");
            }

            this.connectorConfiguration = ConnectorConfiguration.loadFromProperties(buildProperties());

            this.resilienceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tinvest-resilience");
                t.setDaemon(true);
                return t;
            });

            this.client = TInvestExchangeClient.create(
                    this.connectorConfiguration,
                    this.resilienceExecutor,
                    connection.sandboxEnabled(),
                    connection.settings().commissionRate());

            started.set(true);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Имена ключей сверены с константами SDK 1.49.3. */
    private Properties buildProperties() {
        Properties p = new Properties();
        p.setProperty("token", connection.apiKey());
        p.setProperty("sandbox.enabled", Boolean.toString(connection.sandboxEnabled()));

        // Синхронизация справочника тянет весь список инструментов одним ответом, и вселенная
        // опционов в дефолтные 16 МБ SDK может не поместиться — тогда gRPC вернёт
        // RESOURCE_EXHAUSTED вместо данных. Поднимаем лимит: платим только реально
        // принятым объёмом, буфер такого размера заранее не выделяется.
        p.setProperty("connection.max-message-size", Integer.toString(64 * 1024 * 1024));

        ExchangeConnectionSettings s = connection.settings();
        if (s != null) {
            putIfPositive(p, "stream.inactivity-timeout", s.streamInactivityTimeoutSec());
            putIfPositive(p, "stream.ping-delay", s.streamPingDelayMs());
            putIfPositive(p, "stream.market-data.max-streams-count", s.maxMarketDataStreamsCount());
        }
        return p;
    }

    private static void putIfPositive(Properties p, String key, Integer value) {
        if (value != null && value > 0) {
            p.setProperty(key, Integer.toString(value));
        }
    }

    @Override
    public void stop() {
        if (!started.get()) {
            return;
        }

        lifecycleLock.lock();
        try {
            if (!started.get()) {
                return;
            }

            ExchangeClient c = this.client;
            this.client = null;

            ScheduledExecutorService ex = this.resilienceExecutor;
            this.resilienceExecutor = null;

            this.connectorConfiguration = null;
            this.resolvedAccountId = null;

            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("Failed to close T-Invest client: {}", e.getMessage(), e);
                }
            }

            if (ex != null) {
                ex.shutdownNow();
            }

            started.set(false);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void test() {
        if (!started.get()) {
            throw new IllegalStateException("TInvest handler is not started");
        }

        ExchangeClient c = client();
        var accountsApi = c.accounts();
        var accounts = accountsApi.listAccounts();

        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalStateException("У этого токена нет доступных счетов");
        }

        AccountId accountId = chooseAccount(accounts);
        var state = accountsApi.getState(accountId);
        this.resolvedAccountId = accountId;

        log.info("T-Invest test OK: connection={}, accountId={}, sandbox={}",
                connection.name(), accountId.value(), connection.sandboxEnabled());

        if (state.balances() == null || state.balances().isEmpty()) {
            log.info("Balances: <empty>");
        } else {
            for (var b : state.balances()) {
                log.info("Balance: {} available={} blocked={}", b.currency(), b.available(), b.blocked());
            }
        }
    }

    /**
     * Раньше здесь безусловно бралcя accounts.get(0). Для реальных денег так нельзя:
     * торговать нужно ровно на том счёте, который выбрал пользователь.
     */
    private AccountId chooseAccount(java.util.List<ru.larionov.backend.exchange.api.model.account.AccountInfo> accounts) {
        if (connection.hasAccountId()) {
            return accounts.stream()
                    .map(a -> a.id())
                    .filter(id -> connection.accountId().equals(id.value()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Счёт " + connection.accountId() + " недоступен по этому токену. "
                                    + "Выберите счёт заново в настройках подключения."));
        }

        if (accounts.size() == 1) {
            // Выбор однозначен — не заставляем подтверждать очевидное.
            AccountId only = accounts.get(0).id();
            log.info("Подключение «{}»: счёт не задан, доступен единственный — {}",
                    connection.name(), only.value());
            return only;
        }

        throw new IllegalStateException(
                "Доступно несколько счетов (" + accounts.size() + "). "
                        + "Выберите нужный в настройках подключения перед запуском.");
    }

    @Override
    public ExchangeClient client() {
        ExchangeClient local = this.client;
        if (local == null) {
            throw new IllegalStateException("TInvest handler is not started");
        }
        return local;
    }

    @Override
    public StreamHealth marketDataStreamHealth() {
        ExchangeClient c = this.client;
        return c instanceof TInvestExchangeClient t ? t.marketDataStreamHealth() : StreamHealth.disconnected();
    }

    @Override
    public StreamHealth ordersStreamHealth() {
        ExchangeClient c = this.client;
        return c instanceof TInvestExchangeClient t ? t.ordersStreamHealth() : StreamHealth.disconnected();
    }

    @Override
    public void close() {
        stop();
    }
}
