package ru.larionov.backend.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.dto.BotAccountingDto;
import ru.larionov.backend.dto.BotValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.AccountCashService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Рыночная оценка бота и то, что делает её дешёвой.
 *
 * Список ботов опрашивается раз в 4 секунды, поэтому «сводка кэшируется, цена — нет»
 * здесь не деталь реализации, а требование: без него живая торговая система получила бы
 * поток загрузок всего журнала.
 */
class BotValuationServiceTest {

    private static final String BUDGET_CONFIG = """
            {"instrumentUid":"uid-1","lowerPrice":100,"upperPrice":110,"levels":10,
             "budget":10000,"sizingMode":"UNIFORM","profitPolicy":"%s"}
            """;

    private AccountingService accounting;
    private LastPriceCache prices;
    private AccountCashService accountCash;
    private ru.larionov.backend.money.FxRateService fx;
    private BotValuationService service;
    private UUID botId;

    @BeforeEach
    void setUp() {
        accounting = mock(AccountingService.class);
        prices = new LastPriceCache();
        accountCash = mock(AccountCashService.class);
        fx = mock(ru.larionov.backend.money.FxRateService.class);
        // Курс по умолчанию известен: рубли к рублям — единица.
        when(fx.rate(any(), any())).thenAnswer(i -> {
            String from = i.getArgument(0);
            String to = i.getArgument(1);
            return ru.larionov.backend.money.CurrencyCode.sameMoney(from, to)
                    ? java.util.Optional.of(ru.larionov.backend.money.FxRate.identity(to))
                    : java.util.Optional.of(new ru.larionov.backend.money.FxRate(
                            from, to, new java.math.BigDecimal("90"), "CBR", java.time.Instant.now()));
        });
        var appSettings = mock(ru.larionov.backend.service.AppSettingService.class);
        when(appSettings.get(any(), any())).thenAnswer(i -> i.getArgument(1));
        service = new BotValuationService(accounting, prices, accountCash,
                mock(ru.larionov.backend.repository.InstrumentRepository.class), new ObjectMapper(),
                fx, appSettings);
        botId = UUID.randomUUID();
    }

    private BotEntity bot(String config) {
        BotEntity b = BotEntity.builder()
                .id(botId)
                .name("test")
                .strategyConfig(config)
                .build();
        b.setUpdatedAt(Instant.parse("2026-01-08T12:00:00Z"));
        return b;
    }

    /** 100 штук, себестоимость 9500 — средняя 95 за штуку. */
    private BotAccountingDto summary(String realizedPnl) {
        return new BotAccountingDto(false, new BigDecimal("-9500").add(new BigDecimal(realizedPnl)),
                new BigDecimal("9500"), new BigDecimal(realizedPnl), new BigDecimal("50"),
                new BigDecimal("100"), new BigDecimal("95"), "rub");
    }

    /** Позиции нет: всё продано либо ещё ничего не куплено. */
    private BotAccountingDto empty(String realizedPnl) {
        return new BotAccountingDto(false, new BigDecimal(realizedPnl), BigDecimal.ZERO,
                new BigDecimal(realizedPnl), new BigDecimal("50"),
                BigDecimal.ZERO, null, "rub");
    }

    // ==============================
    // РАСЧЁТ
    // ==============================

    @Test
    void marketValueAndUnrealizedPnlUseTheStreamedPrice() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("0"));
        prices.put(botId, new BigDecimal("100"), Instant.now());

        BotValuationDto v = service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));

        // 100 штук по 100 = 10000, себестоимость 9500.
        assertThat(v.marketValue()).isEqualByComparingTo("10000");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("500");
        assertThat(v.totalPnl()).isEqualByComparingTo("500");
        // Тот же результат через среднюю цену входа: 100 × (100 − 95).
        assertThat(v.unrealizedPnl()).isEqualByComparingTo(
                new BigDecimal("100").subtract(v.averageEntryPrice())
                        .multiply(v.openQuantity()));
    }

    @Test
    void withOpenPositionButNoPriceOnlyRealizedPnlSurvives() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("300"));

        BotValuationDto v = service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));

        assertThat(v.realizedPnl()).isEqualByComparingTo("300");
        assertThat(v.lastPrice()).isNull();
        assertThat(v.marketValue()).isNull();
        assertThat(v.unrealizedPnl()).isNull();
        assertThat(v.totalPnl()).isNull();
        assertThat(v.equity()).isNull();
    }

    /**
     * Бот с бюджетом, но ещё ничего не купивший, обязан показывать баланс.
     * Раньше он писал «нет актуальной цены», хотя цена ни на что не влияла:
     * оценивать было нечего.
     */
    @Test
    void botWithoutAPositionShowsItsBalanceEvenWithoutAPrice() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(empty("0"));

        BotValuationDto v = service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));

        assertThat(v.lastPrice()).isNull();
        assertThat(v.marketValue()).isEqualByComparingTo("0");
        assertThat(v.unrealizedPnl()).isEqualByComparingTo("0");
        assertThat(v.totalPnl()).isEqualByComparingTo("0");
        assertThat(v.equity()).isEqualByComparingTo("10000");
    }

    @Test
    void balanceOfAnEmptyBotFollowsRealizedProfitAccordingToPolicy() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(empty("700"));

        // WITHDRAW: прибыль выведена, в обороте остался ровно бюджет.
        assertThat(service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW"))).equity())
                .isEqualByComparingTo("10000");

        service.forget(botId);

        // COMPOUND: прибыль осталась внутри.
        assertThat(service.valuation(bot(BUDGET_CONFIG.formatted("COMPOUND"))).equity())
                .isEqualByComparingTo("10700");
    }

    /**
     * Развёрнутая формула баланса обязана совпадать со свёрнутой:
     * бюджет + реализованный − выведенный − себестоимость + рыночная стоимость.
     */
    @Test
    void balanceMatchesItsComponentDecomposition() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("300"));
        prices.put(botId, new BigDecimal("100"), Instant.now());

        BotValuationDto v = service.valuation(bot(BUDGET_CONFIG.formatted("COMPOUND")));

        BigDecimal byComponents = v.budget()
                .add(v.realizedPnl())
                .subtract(v.withdrawnProfit())
                .subtract(v.costBasisOpen())
                .add(v.marketValue());

        assertThat(v.equity()).isEqualByComparingTo(byComponents);
    }

    @Test
    void bothProfitPoliciesReportTheSameTotalWealth() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("300"));
        prices.put(botId, new BigDecimal("100"), Instant.now());

        BotValuationDto withdraw = service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));
        service.forget(botId);
        prices.put(botId, new BigDecimal("100"), Instant.now());
        BotValuationDto compound = service.valuation(bot(BUDGET_CONFIG.formatted("COMPOUND")));

        // WITHDRAW: бюджет заморожен, прибыль лежит отдельной строкой.
        assertThat(withdraw.workingBudget()).isEqualByComparingTo("10000");
        assertThat(withdraw.equity()).isEqualByComparingTo("10500");
        assertThat(withdraw.withdrawnProfit()).isEqualByComparingTo("300");

        // COMPOUND: прибыль внутри бюджета.
        assertThat(compound.workingBudget()).isEqualByComparingTo("10300");
        assertThat(compound.equity()).isEqualByComparingTo("10800");
        assertThat(compound.withdrawnProfit()).isEqualByComparingTo("0");

        // Политики различаются экспозицией, а не результатом.
        assertThat(withdraw.equity().add(withdraw.withdrawnProfit()))
                .isEqualByComparingTo(compound.equity().add(compound.withdrawnProfit()));
    }

    @Test
    void legacyBotWithoutBudgetHasNoEquityButKeepsPnl() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("300"));
        prices.put(botId, new BigDecimal("100"), Instant.now());

        BotValuationDto v = service.valuation(bot("""
                {"instrumentUid":"uid-1","lowerPrice":100,"upperPrice":110,"levels":10,"lotsPerOrder":1}
                """));

        // Лимит капитала — это ограничение риска, а не деньги: equity из него не выдумываем.
        assertThat(v.budget()).isNull();
        assertThat(v.equity()).isNull();
        assertThat(v.totalPnl()).isEqualByComparingTo("800");
    }

    @Test
    void brokenConfigStillYieldsRealizedPnl() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("300"));

        BotValuationDto v = service.valuation(bot("{\"levels\":\"нет такого\"}"));

        assertThat(v.realizedPnl()).isEqualByComparingTo("300");
        assertThat(v.budget()).isNull();
    }

    // ==============================
    // КЭШ
    // ==============================

    @Test
    void ledgerSummaryIsComputedOnceAndReusedByThePoll() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("0"));
        BotEntity bot = bot(BUDGET_CONFIG.formatted("WITHDRAW"));

        for (int i = 0; i < 10; i++) {
            service.valuation(bot);
        }

        verify(accounting, times(1)).summaryFast(any(), anyBoolean());
        // Ремонтный проход по книге на путь списка не попадает вообще.
        verify(accounting, never()).summary(any(), anyBoolean());
    }

    @Test
    void priceIsRereadOnEveryCallEvenWhenTheSummaryIsCached() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("0"));
        BotEntity bot = bot(BUDGET_CONFIG.formatted("WITHDRAW"));

        prices.put(botId, new BigDecimal("100"), Instant.now());
        assertThat(service.valuation(bot).marketValue()).isEqualByComparingTo("10000");

        prices.put(botId, new BigDecimal("120"), Instant.now());
        assertThat(service.valuation(bot).marketValue()).isEqualByComparingTo("12000");

        verify(accounting, times(1)).summaryFast(any(), anyBoolean());
    }

    @Test
    void ledgerChangeInvalidatesTheCachedSummary() {
        when(accounting.summaryFast(any(), anyBoolean()))
                .thenReturn(summary("0"))
                .thenReturn(summary("700"));
        BotEntity bot = bot(BUDGET_CONFIG.formatted("WITHDRAW"));

        assertThat(service.valuation(bot).realizedPnl()).isEqualByComparingTo("0");

        service.onLedgerChanged(new LedgerChangedEvent(botId, false));

        assertThat(service.valuation(bot).realizedPnl()).isEqualByComparingTo("700");
        verify(accounting, times(2)).summaryFast(any(), anyBoolean());
    }

    @Test
    void detailViewGoesThroughTheRepairingPath() {
        when(accounting.summary(any(), anyBoolean())).thenReturn(summary("300"));

        BotValuationDto v = service.accounting(bot(BUDGET_CONFIG.formatted("WITHDRAW")), null);

        assertThat(v.realizedPnl()).isEqualByComparingTo("300");
        verify(accounting, times(1)).summary(any(), anyBoolean());
        verify(accounting, never()).summaryFast(any(), anyBoolean());
    }

    @Test
    void forgettingABotDropsItsCachedPriceToo() {
        when(accounting.summaryFast(any(), anyBoolean())).thenReturn(summary("0"));
        prices.put(botId, new BigDecimal("100"), Instant.now());
        service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));

        service.forget(botId);

        assertThat(prices.get(botId)).isEmpty();
        service.valuation(bot(BUDGET_CONFIG.formatted("WITHDRAW")));
        verify(accounting, times(2)).summaryFast(any(), anyBoolean());
    }
}
