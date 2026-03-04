package ru.larionov.backend.exchange.tinvest;

import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.service.ExchangeHandler;
import ru.ttech.piapi.core.connector.ConnectorConfiguration;

/**
 * T-Invest (Tinkoff Invest) exchange handler.
 *
 * Responsibilities:
 *  - Own SDK lifecycle (create/start/stop)
 *  - Provide client facade used by the rest of the app
 *  - Provide lightweight health-check (test)
 *
 * NOTE: Конкретная привязка к SDK будет внутри пакета tinvest; наружу отдаём только ExchangeClient.
 */
@Slf4j
public final class TInvestExchangeHandler implements ExchangeHandler {

    private final ExchangeConnectionEntity connection;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile ExchangeClient client; // created on start
    private volatile ConnectorConfiguration connectorConfiguration;

    private volatile ScheduledExecutorService resilienceExecutor;

    public TInvestExchangeHandler(ExchangeConnectionEntity connection) {
        this.connection = connection;
        if (connection == null) {
            throw new IllegalArgumentException("connection must not be null");
        }
    }

    @Override
    public UUID connectionId() {
        return connection.getId();
    }

    @Override
    public ExchangeType exchangeType() {
        // adjust enum constant name to your project
        return ExchangeType.T_INVEST;
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

            if (connection.getApiKey() == null || connection.getApiKey().isBlank()) {
                throw new IllegalStateException("T-Invest apiKey/token is empty for connectionId=" + connection.getId());
            }

            Properties p = new Properties();
            p.setProperty("token", connection.getApiKey());

            p.setProperty("sandbox.enabled", Boolean.toString(connection.isSandboxEnabled()));

            this.connectorConfiguration = ConnectorConfiguration.loadFromProperties(p);

            this.resilienceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tinvest-resilience");
                t.setDaemon(true);
                return t;
            });

            // Create real client via factory method (builds ServiceStubFactory + resilience wrappers internally)
            this.client = TInvestExchangeClient.create(this.connectorConfiguration, this.resilienceExecutor);

            started.set(true);
        } finally {
            lifecycleLock.unlock();
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

            // Close SDK client first
            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("Failed to close T-Invest client: {}", e.getMessage(), e);
                }
            }

            // Then stop executor used by resilience
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
        log.info("Testing T-Invest Exchange");
        if (!started.get()) {
            throw new IllegalStateException("TInvest handler is not started");
        }

        try {
            ExchangeClient c = client();

            var accountsApi = c.accounts();
            var accounts = accountsApi.listAccounts();
            if (accounts == null || accounts.isEmpty()) {
                log.warn("T-Invest test: no accounts returned");
                return;
            }

            // For now: pick the first account. Later we can persist a preferred accountId in connection settings.
            var acc = accounts.get(0);
            var state = accountsApi.getState(acc.id());

            log.info("T-Invest test OK. accountId={}, name={}, type={}, sandbox={}",
                    acc.id().value(), acc.name(), acc.type(), acc.sandbox());

            if (state.balances() == null || state.balances().isEmpty()) {
                log.info("Balances: <empty>");
            } else {
                for (var b : state.balances()) {
                    log.info("Balance: {} available={} blocked={}", b.currency(), b.available(), b.blocked());
                }
            }
        } catch (Exception e) {
            log.warn("T-Invest test failed: {}", e.getMessage(), e);
            throw e;
        }
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
    public void close() {
        stop();
    }
}
