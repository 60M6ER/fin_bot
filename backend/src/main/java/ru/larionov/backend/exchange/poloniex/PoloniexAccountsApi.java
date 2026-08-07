package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.response.spot.AccountBalance;
import com.poloniex.api.client.spot.model.response.spot.Balance;
import lombok.RequiredArgsConstructor;
import ru.larionov.backend.exchange.api.AccountsApi;
import ru.larionov.backend.exchange.api.enums.AccountType;
import ru.larionov.backend.exchange.api.model.account.AccountInfo;
import ru.larionov.backend.exchange.api.model.account.AccountState;
import ru.larionov.backend.exchange.api.model.account.MoneyBalance;
import ru.larionov.backend.exchange.api.model.account.Position;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Счета и балансы Poloniex.
 *
 * <h3>Что такое «позиция» на споте</h3>
 * Позиции как отдельной сущности у спотовой биржи нет: владение выражено остатком
 * базовой монеты на кошельке. Поэтому позицией по BTC_USDT мы называем баланс BTC.
 *
 * У этого есть следствие, которое важно понимать: остаток кошелька ОБЩИЙ. Если тем же
 * ключом торгует что-то ещё или монеты просто лежат с прошлых времён, сверка увидит
 * расхождение с журналом — и будет права. Разделить чужие монеты и свои биржа не даёт,
 * поэтому под каждого бота лучше держать отдельный субсчёт.
 */
@RequiredArgsConstructor
public class PoloniexAccountsApi implements AccountsApi {

    /** Торгуем только со спотового кошелька: маржа и фьючерсы адаптером не поддержаны. */
    private static final String SPOT = "SPOT";

    private final PoloniexRest rest;
    private final PoloniexSymbols symbols;

    @Override
    public List<AccountInfo> listAccounts() {
        var accounts = rest.call("список счетов", rest.api().accounts());
        if (accounts == null) {
            return List.of();
        }
        return accounts.stream()
                .filter(a -> SPOT.equalsIgnoreCase(a.getAccountType()))
                .map(a -> new AccountInfo(
                        new AccountId(a.getAccountId()),
                        a.getAccountType(),
                        AccountType.SPOT,
                        false))
                .toList();
    }

    @Override
    public AccountState getState(AccountId accountId) {
        List<MoneyBalance> balances = new ArrayList<>();
        for (Balance balance : spotBalances()) {
            if (balance.getCurrency() == null) {
                continue;
            }
            balances.add(new MoneyBalance(
                    balance.getCurrency().toUpperCase(Locale.ROOT),
                    decimal(balance.getAvailable()),
                    decimal(balance.getHold())));
        }

        // Позиции отдельным списком не отдаём: на споте они и есть балансы монет,
        // и дублировать их значило бы завести второй источник правды об одном и том же.
        return new AccountState(accountId, balances, List.of(), Instant.now());
    }

    @Override
    public Optional<Position> getPosition(AccountId accountId, InstrumentId instrumentId) {
        String symbol = PoloniexSymbols.symbolOf(instrumentId);
        String base = symbols.limits(symbol).base();
        if (base == null) {
            return Optional.empty();
        }

        BigDecimal quantity = BigDecimal.ZERO;
        for (Balance balance : spotBalances()) {
            if (base.equalsIgnoreCase(balance.getCurrency())) {
                // Заблокированное заявками тоже наше: продажа в стакане не перестаёт
                // быть позицией, пока не исполнилась.
                quantity = decimal(balance.getAvailable()).add(decimal(balance.getHold()));
                break;
            }
        }
        return Optional.of(new Position(instrumentId, quantity, null, null, null));
    }

    private List<Balance> spotBalances() {
        List<AccountBalance> accounts = rest.call("балансы счёта", rest.api().balances(SPOT));
        if (accounts == null) {
            return List.of();
        }
        List<Balance> balances = new ArrayList<>();
        for (AccountBalance account : accounts) {
            if (account.getBalances() != null) {
                balances.addAll(account.getBalances());
            }
        }
        return balances;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
