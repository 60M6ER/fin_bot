package ru.larionov.backend.exchange.poloniex;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;
import ru.larionov.backend.exchange.api.model.account.AccountInfo;
import ru.larionov.backend.exchange.api.model.account.MoneyBalance;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.service.ExchangeHandler;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Подключение к Poloniex.
 *
 * Устроен так же, как хендлер T-Invest, и это не совпадение: жизненный цикл
 * подключения (поднять, проверить, погасить) — общая часть контракта, и различаться
 * ему незачем.
 *
 * <h3>Про песочницу</h3>
 * У Poloniex её нет. Флаг {@code sandboxEnabled} подключения здесь ничего не значит,
 * и притворяться иначе опасно: пользователь, поставивший галочку, торговал бы
 * реальными деньгами, считая, что тренируется. Поэтому при поднятии подключения
 * с этим флагом мы падаем с объяснением, а не работаем молча.
 */
@Slf4j
public final class PoloniexExchangeHandler implements ExchangeHandler {

    private static final String HOST = "https://api.poloniex.com";
    private static final String PUBLIC_WS = "wss://ws.poloniex.com/ws/public";
    private static final String PRIVATE_WS = "wss://ws.poloniex.com/ws/private";

    private final ExchangeConnectionContext connection;

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile PoloniexExchangeClient client;
    private volatile AccountId resolvedAccountId;

    public PoloniexExchangeHandler(ExchangeConnectionContext connection) {
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
        return ExchangeType.POLONIEX;
    }

    public ExchangeConnectionContext context() {
        return connection;
    }

    @Override
    public AccountId tradingAccountId() {
        AccountId resolved = resolvedAccountId;
        if (resolved != null) {
            return resolved;
        }
        if (connection.hasAccountId()) {
            return new AccountId(connection.accountId());
        }
        throw new IllegalStateException(
                "Не определён торговый счёт подключения Poloniex. Запустите подключение заново.");
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

            if (connection.apiKey() == null || connection.apiKey().isBlank()
                    || connection.apiSecret() == null || connection.apiSecret().isBlank()) {
                throw new IllegalStateException(
                        "Не заданы ключ и секрет Poloniex для подключения «" + connection.name() + "». "
                                + "Укажите их в разделе «Биржи» → «Ключи».");
            }

            if (connection.sandboxEnabled()) {
                throw new IllegalStateException(
                        "У Poloniex нет песочницы: подключение с включённым режимом песочницы "
                                + "торговало бы реальными деньгами. Выключите режим песочницы "
                                + "и проверяйте бота в бумажном режиме (dry-run).");
            }

            this.client = new PoloniexExchangeClient(
                    HOST, PUBLIC_WS, PRIVATE_WS, connection.apiKey(), connection.apiSecret());
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

            PoloniexExchangeClient c = this.client;
            this.client = null;
            this.resolvedAccountId = null;

            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    log.warn("Не удалось закрыть клиент Poloniex: {}", e.getMessage(), e);
                }
            }
            started.set(false);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Health-check: ключ рабочий и спотовый счёт найден.
     *
     * Проверяем именно приватным вызовом. Публичный эндпоинт ответит и без ключа,
     * то есть подтвердит доступность биржи, но не право торговать — а активация
     * подключения обещает пользователю именно второе.
     */
    @Override
    public void test() {
        if (!started.get()) {
            throw new IllegalStateException("Подключение Poloniex не поднято");
        }

        ExchangeClient c = client();
        List<AccountInfo> accounts = c.accounts().listAccounts();
        if (accounts == null || accounts.isEmpty()) {
            throw new IllegalStateException(
                    "У этого ключа нет спотового счёта Poloniex. Проверьте права ключа.");
        }

        AccountId accountId = chooseAccount(accounts);
        var state = c.accounts().getState(accountId);
        this.resolvedAccountId = accountId;

        log.info("Poloniex test OK: connection={}, accountId={}", connection.name(), accountId.value());
        if (state.balances() == null || state.balances().isEmpty()) {
            log.info("Balances: <empty>");
        } else {
            for (MoneyBalance b : state.balances()) {
                if (b.available().signum() > 0 || b.blocked().signum() > 0) {
                    log.info("Balance: {} available={} blocked={}", b.currency(), b.available(), b.blocked());
                }
            }
        }
    }

    private AccountId chooseAccount(List<AccountInfo> accounts) {
        if (connection.hasAccountId()) {
            return accounts.stream()
                    .map(AccountInfo::id)
                    .filter(id -> connection.accountId().equals(id.value()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Счёт " + connection.accountId() + " недоступен по этому ключу. "
                                    + "Выберите счёт заново в настройках подключения."));
        }

        if (accounts.size() == 1) {
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
            throw new IllegalStateException("Подключение Poloniex не поднято");
        }
        return local;
    }

    @Override
    public StreamHealth marketDataStreamHealth() {
        PoloniexExchangeClient c = this.client;
        return c == null ? StreamHealth.disconnected() : c.marketDataStreamHealth();
    }

    @Override
    public StreamHealth ordersStreamHealth() {
        PoloniexExchangeClient c = this.client;
        return c == null ? StreamHealth.disconnected() : c.ordersStreamHealth();
    }

    /** Песочницы у биржи нет — подключение всегда боевое. */
    @Override
    public boolean sandbox() {
        return false;
    }

    @Override
    public void close() {
        stop();
    }
}
