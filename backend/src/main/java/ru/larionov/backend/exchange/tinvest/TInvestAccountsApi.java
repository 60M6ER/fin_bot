package ru.larionov.backend.exchange.tinvest;

import ru.larionov.backend.exchange.api.AccountsApi;
import ru.larionov.backend.exchange.api.model.account.*;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.tinkoff.piapi.contract.v1.*;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * T-Invest implementation of AccountsApi.
 */
public class TInvestAccountsApi implements AccountsApi {

    private final TInvestExchangeClient client;

    public TInvestAccountsApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<AccountInfo> listAccounts() {
        GetAccountsRequest request = GetAccountsRequest.getDefaultInstance();

        GetAccountsResponse response = client.usersStub()
                .callSyncMethod(
                        UsersServiceGrpc.getGetAccountsMethod(),
                        stub -> stub.getAccounts(request)
                );

        boolean sandbox = isSandboxMode();

        List<AccountInfo> result = new ArrayList<>(response.getAccountsCount());
        for (ru.tinkoff.piapi.contract.v1.Account acc : response.getAccountsList()) {
            AccountId id = new AccountId(acc.getId());
            String name = acc.getName();
            ru.larionov.backend.exchange.api.enums.AccountType type = mapAccountType(acc.getType());

            result.add(new AccountInfo(id, name, type, sandbox));
        }

        return result;
    }

    @Override
    public AccountState getState(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");

        // 1) Денежные балансы (available/blocked) берём из GetPositions.
        PositionsRequest positionsRequest = PositionsRequest.newBuilder()
                .setAccountId(accountId.value())
                .build();

        PositionsResponse positionsResponse = client.operationsStub()
                .callSyncMethod(
                        OperationsServiceGrpc.getGetPositionsMethod(),
                        stub -> stub.getPositions(positionsRequest)
                );

        List<MoneyBalance> balances = mapBalances(positionsResponse);

        // 2) Позиции с ценами/доходностью берём из GetPortfolio.
        PortfolioRequest portfolioRequest = PortfolioRequest.newBuilder()
                .setAccountId(accountId.value())
                .build();

        PortfolioResponse portfolioResponse = client.operationsStub()
                .callSyncMethod(
                        OperationsServiceGrpc.getGetPortfolioMethod(),
                        stub -> stub.getPortfolio(portfolioRequest)
                );

        List<Position> positions = mapPortfolioPositions(portfolioResponse);

        return new AccountState(accountId, balances, positions, Instant.now());
    }

    @Override
    public Optional<Position> getPosition(AccountId accountId, InstrumentId instrumentId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(instrumentId, "instrumentId");

        AccountState state = getState(accountId);

        for (Position p : state.positions()) {
            if (sameInstrument(p.instrumentId(), instrumentId)) {
                return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    private static boolean sameInstrument(InstrumentId a, InstrumentId b) {
        if (a == null || b == null) return false;
        if (a.uid() != null && b.uid() != null && !a.uid().isBlank() && !b.uid().isBlank()) {
            return a.uid().equals(b.uid());
        }
        if (a.figi() != null && b.figi() != null && !a.figi().isBlank() && !b.figi().isBlank()) {
            return a.figi().equals(b.figi());
        }
        return false;
    }

    private List<MoneyBalance> mapBalances(PositionsResponse response) {
        List<MoneyBalance> balances = new ArrayList<>();

        // Money positions
        for (MoneyValue mv : response.getMoneyList()) {
            balances.add(new MoneyBalance(
                    mv.getCurrency(),
                    moneyValueToBigDecimal(mv),
                    BigDecimal.ZERO
            ));
        }

        // Blocked money (if present)
        for (MoneyValue mv : response.getBlockedList()) {
            String ccy = mv.getCurrency();
            BigDecimal blocked = moneyValueToBigDecimal(mv);

            // merge by currency
            boolean merged = false;
            for (int i = 0; i < balances.size(); i++) {
                MoneyBalance b = balances.get(i);
                if (Objects.equals(b.currency(), ccy)) {
                    balances.set(i, new MoneyBalance(ccy, b.available(), blocked));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                balances.add(new MoneyBalance(ccy, BigDecimal.ZERO, blocked));
            }
        }

        return balances;
    }

    private List<Position> mapPositions(PositionsResponse response) {
        List<Position> positions = new ArrayList<>();

        // Securities positions
        for (PositionsSecurities sec : response.getSecuritiesList()) {
            InstrumentId instrumentId = new InstrumentId(
                    // SDK versions differ: instrumentUid may be present
                    safeGetInstrumentUid(sec),
                    sec.getFigi()
            );

            BigDecimal qty = BigDecimal.valueOf(sec.getBalance());

            positions.add(new Position(
                    instrumentId,
                    qty,
                    null,
                    null,
                    null
            ));
        }

        return positions;
    }

    private List<Position> mapPortfolioPositions(PortfolioResponse response) {
        List<Position> positions = new ArrayList<>();

        // Реальные позиции
        for (PortfolioPosition p : response.getPositionsList()) {
            InstrumentId instrumentId = new InstrumentId(
                    safeGetInstrumentUid(p),
                    p.getFigi()
            );

            BigDecimal qty = quotationToBigDecimal(p.getQuantity());
            BigDecimal avg = moneyValueToBigDecimal(p.getAveragePositionPrice());
            BigDecimal cur = moneyValueToBigDecimal(p.getCurrentPrice());
            BigDecimal pnl = quotationToBigDecimal(p.getExpectedYield());

            positions.add(new Position(
                    instrumentId,
                    qty,
                    avg,
                    cur,
                    pnl
            ));
        }

        // Виртуальные позиции (например, фьючи) — пока добавляем так же, если понадобятся позже
        for (VirtualPortfolioPosition p : response.getVirtualPositionsList()) {
            InstrumentId instrumentId = new InstrumentId(
                    safeGetInstrumentUid(p),
                    p.getFigi()
            );

            BigDecimal qty = quotationToBigDecimal(p.getQuantity());
            BigDecimal avg = moneyValueToBigDecimal(p.getAveragePositionPrice());
            BigDecimal cur = moneyValueToBigDecimal(p.getCurrentPrice());
            BigDecimal pnl = quotationToBigDecimal(p.getExpectedYield());

            positions.add(new Position(
                    instrumentId,
                    qty,
                    avg,
                    cur,
                    pnl
            ));
        }

        return positions;
    }

    private static String safeGetInstrumentUid(PortfolioPosition p) {
        try {
            var m = p.getClass().getMethod("getInstrumentUid");
            Object v = m.invoke(p);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String safeGetInstrumentUid(VirtualPortfolioPosition p) {
        try {
            var m = p.getClass().getMethod("getInstrumentUid");
            Object v = m.invoke(p);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static BigDecimal quotationToBigDecimal(Quotation q) {
        if (q == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(q.getUnits());
        BigDecimal nano = BigDecimal.valueOf(q.getNano(), 9);
        return units.add(nano);
    }

    private static String safeGetInstrumentUid(PositionsSecurities sec) {
        try {
            Method m = sec.getClass().getMethod("getInstrumentUid");
            Object v = m.invoke(sec);
            if (v instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ru.larionov.backend.exchange.api.enums.AccountType mapAccountType(ru.tinkoff.piapi.contract.v1.AccountType type) {
        if (type == null) {
            return ru.larionov.backend.exchange.api.enums.AccountType.UNKNOWN;
        }

        // Map tolerantly by name to our domain enum.
        String name = type.name();
        if (name == null) {
            return ru.larionov.backend.exchange.api.enums.AccountType.UNKNOWN;
        }

        return switch (name) {
            case "ACCOUNT_TYPE_TINKOFF" -> ru.larionov.backend.exchange.api.enums.AccountType.BROKER;
            case "ACCOUNT_TYPE_TINKOFF_IIS" -> ru.larionov.backend.exchange.api.enums.AccountType.BROKER;
            case "ACCOUNT_TYPE_INVEST_BOX" -> ru.larionov.backend.exchange.api.enums.AccountType.UNKNOWN;
            default -> ru.larionov.backend.exchange.api.enums.AccountType.UNKNOWN;
        };
    }

    private static BigDecimal moneyValueToBigDecimal(MoneyValue mv) {
        if (mv == null) {
            return null;
        }
        BigDecimal units = BigDecimal.valueOf(mv.getUnits());
        BigDecimal nano = BigDecimal.valueOf(mv.getNano(), 9);
        return units.add(nano);
    }

    /**
     * We keep sandbox flag in AccountInfo. In our app it is a connection-level setting,
     * so we try to read it from the client via reflection to avoid hard coupling.
     */
    private boolean isSandboxMode() {
        try {
            Method m = client.getClass().getMethod("isSandbox");
            Object v = m.invoke(client);
            if (v instanceof Boolean b) {
                return b;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
