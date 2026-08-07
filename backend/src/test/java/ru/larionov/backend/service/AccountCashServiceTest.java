package ru.larionov.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.AccountsApi;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.account.AccountState;
import ru.larionov.backend.exchange.api.model.account.MoneyBalance;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.instrument.ExchangeMeta;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Выбор валюты, в которой показываются деньги подключения.
 *
 * Тест существует из-за конкретной ошибки: валюта выбиралась по наибольшему
 * ОСТАТКУ, и спотовый кошелёк с 0.894 USDT и пылью из 10 HTX показывал баланс
 * «10,0367 HTX». Дальше ломалось всё, что от валюты зависит: курса HTX никто
 * не знает, и сводный портфель отказывался считаться целиком.
 */
class AccountCashServiceTest {

    private final UUID connectionId = UUID.randomUUID();

    private ExchangeRuntimeService runtimeService;
    private AccountsApi accounts;
    private AccountCashService service;

    @BeforeEach
    void setUp() {
        runtimeService = mock(ExchangeRuntimeService.class);
        ExchangeHandler handler = mock(ExchangeHandler.class);
        ExchangeClient client = mock(ExchangeClient.class);
        accounts = mock(AccountsApi.class);

        when(runtimeService.get(connectionId)).thenReturn(Optional.of(handler));
        when(handler.client()).thenReturn(client);
        when(handler.tradingAccountId()).thenReturn(new AccountId("acc-1"));
        when(client.accounts()).thenReturn(accounts);
        when(client.meta()).thenReturn(poloniexMeta());

        service = new AccountCashService(runtimeService);
    }

    private static ExchangeMeta poloniexMeta() {
        return new ExchangeMeta(ExchangeType.POLONIEX, false, true, true, false, false,
                List.of("USDT", "USDC"), true);
    }

    private void balances(MoneyBalance... items) {
        when(accounts.getState(any())).thenReturn(
                new AccountState(new AccountId("acc-1"), List.of(items), List.of(), Instant.now()));
    }

    private static MoneyBalance money(String currency, String available) {
        return new MoneyBalance(currency, new BigDecimal(available), BigDecimal.ZERO);
    }

    /** Тот самый случай: пыли по числу больше, но деньги — стейблкойн. */
    @Test
    void dustCoinDoesNotOutweighTheStablecoin() {
        balances(
                money("HTX", "10.036655573564543137"),
                money("USDT", "0.894143631209862891"),
                money("TRX", "0.0025660614"),
                money("BTC", "0.000000528397260264"));

        assertThat(service.dominantCurrency(connectionId))
                .as("деньгами на споте служит стейблкойн, а не самая многочисленная монета")
                .isEqualTo("USDT");
    }

    /** Приоритет объявлен порядком: USDT важнее USDC, даже когда его меньше. */
    @Test
    void declarationOrderDecidesBetweenSettlementCurrencies() {
        balances(money("USDC", "500"), money("USDT", "1"));

        assertThat(service.dominantCurrency(connectionId)).isEqualTo("USDT");
    }

    /** Расчётной валюты нет вовсе — показываем основную с нулём, а не случайную монету. */
    @Test
    void withoutAnyCashCurrencyThePrimaryOneIsReportedRatherThanACoin() {
        balances(money("HTX", "10"), money("XMR", "0.00000005"));

        assertThat(service.dominantCurrency(connectionId))
                .as("«10 HTX» деньгами не являются ни при каких обстоятельствах")
                .isEqualTo("USDT");
    }

    /** Нулевой остаток расчётной валюты не считается наличием денег. */
    @Test
    void zeroBalanceInCashCurrencyIsSkipped() {
        balances(money("USDT", "0"), money("USDC", "25"));

        assertThat(service.dominantCurrency(connectionId)).isEqualTo("USDC");
    }

    /** Биржа расчётных валют не объявила — остаётся прежнее поведение. */
    @Test
    void exchangeWithoutDeclaredCashCurrenciesFallsBackToLargestBalance() {
        ExchangeHandler handler = mock(ExchangeHandler.class);
        ExchangeClient client = mock(ExchangeClient.class);
        when(runtimeService.get(connectionId)).thenReturn(Optional.of(handler));
        when(handler.client()).thenReturn(client);
        when(handler.tradingAccountId()).thenReturn(new AccountId("acc-1"));
        when(client.accounts()).thenReturn(accounts);
        when(client.meta()).thenReturn(new ExchangeMeta(
                ExchangeType.T_INVEST, true, true, true, true, true, List.of(), false));

        balances(money("AAA", "3"), money("BBB", "7"));

        assertThat(service.dominantCurrency(connectionId)).isEqualTo("BBB");
    }

    /** Подключение не поднято — валюты нет, и выдумывать её нельзя. */
    @Test
    void stoppedConnectionHasNoCurrency() {
        UUID other = UUID.randomUUID();
        when(runtimeService.get(other)).thenReturn(Optional.empty());

        assertThat(service.dominantCurrency(other)).isNull();
    }
}
