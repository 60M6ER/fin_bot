package ru.larionov.backend.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.dto.BotAccountingDto;
import ru.larionov.backend.dto.ConnectionValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.AccountCashService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Сводный кошелёк подключения.
 *
 * Ключевая проверка — сходимость: сумма по ботам плюс нераспределённый остаток
 * обязана равняться свободным деньгам счёта плюс рыночная стоимость всех позиций.
 * Если эти два способа посчитать одно и то же расходятся, показывать нельзя ни один.
 */
class ConnectionValuationTest {

    private final UUID connectionId = UUID.randomUUID();

    private AccountingService accounting;
    private LastPriceCache prices;
    private AccountCashService accountCash;
    private BotValuationService service;

    @BeforeEach
    void setUp() {
        accounting = mock(AccountingService.class);
        prices = new LastPriceCache();
        accountCash = mock(AccountCashService.class);
        service = new BotValuationService(accounting, prices, accountCash, new ObjectMapper());
        when(accountCash.dominantCurrency(any())).thenReturn("RUB");
    }

    private BotEntity bot(UUID id, String budget, String policy) {
        BotEntity b = BotEntity.builder()
                .id(id)
                .name("bot-" + id)
                .exchangeConnectionId(connectionId)
                .strategyConfig("""
                        {"instrumentUid":"uid","lowerPrice":100,"upperPrice":110,"levels":10,
                         "budget":%s,"sizingMode":"UNIFORM","profitPolicy":"%s"}
                        """.formatted(budget, policy))
                .build();
        b.setUpdatedAt(Instant.parse("2026-01-08T12:00:00Z"));
        return b;
    }

    private BotEntity legacyBot(UUID id) {
        BotEntity b = BotEntity.builder()
                .id(id)
                .name("legacy")
                .exchangeConnectionId(connectionId)
                .strategyConfig("""
                        {"instrumentUid":"uid","lowerPrice":100,"upperPrice":110,
                         "levels":10,"lotsPerOrder":1}
                        """)
                .build();
        b.setUpdatedAt(Instant.parse("2026-01-08T12:00:00Z"));
        return b;
    }

    /** @param openShares 0 — позиции нет */
    private BotAccountingDto stateOf(String costBasis, long openShares, String realizedPnl) {
        return new BotAccountingDto(false, new BigDecimal(realizedPnl).subtract(new BigDecimal(costBasis)),
                new BigDecimal(costBasis), new BigDecimal(realizedPnl), BigDecimal.ZERO,
                openShares / 10, openShares == 0 ? null : new BigDecimal(costBasis)
                .divide(BigDecimal.valueOf(openShares), 9, java.math.RoundingMode.HALF_UP),
                "RUB", openShares);
    }

    @Test
    void sumsBotBalancesAndTheUnallocatedRemainder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Бот A вложил 4000 в позицию, которая сейчас стоит 4500.
        when(accounting.summaryFast(eq(a), anyBoolean())).thenReturn(stateOf("4000", 100, "0"));
        prices.put(a, new BigDecimal("45"), Instant.now());
        // Бот B ничего не купил.
        when(accounting.summaryFast(eq(b), anyBoolean())).thenReturn(stateOf("0", 0, "0"));

        // Свободных денег на счёте: 10000 (бот A) − 4000 (вложено) + 5000 (бот B) + 3000 ничьих.
        when(accountCash.available(any(), eq("RUB"))).thenReturn(new BigDecimal("14000"));

        ConnectionValuationDto v = service.connectionValuation(
                connectionId, List.of(bot(a, "10000", "WITHDRAW"), bot(b, "5000", "WITHDRAW")));

        assertThat(v.botCount()).isEqualTo(2);
        assertThat(v.valuedBotCount()).isEqualTo(2);
        assertThat(v.incomplete()).isFalse();
        assertThat(v.allocatedBudget()).isEqualByComparingTo("15000");
        // A: 10000 + 500 нереализованного, B: ровно свой бюджет.
        assertThat(v.botsBalance()).isEqualByComparingTo("15500");
        // Свободные деньги ботов: (10000−4000) + (5000−0) = 11000. Ничьих осталось 3000.
        assertThat(v.unallocatedCash()).isEqualByComparingTo("3000");
        assertThat(v.total()).isEqualByComparingTo("18500");

        // Сходимость: то же самое = свободные деньги счёта + рыночная стоимость позиций.
        assertThat(v.total()).isEqualByComparingTo(new BigDecimal("14000").add(new BigDecimal("4500")));
    }

    @Test
    void negativeRemainderMeansMoreWasAllocatedThanTheAccountHolds() {
        UUID a = UUID.randomUUID();
        when(accounting.summaryFast(eq(a), anyBoolean())).thenReturn(stateOf("0", 0, "0"));
        when(accountCash.available(any(), eq("RUB"))).thenReturn(new BigDecimal("3000"));

        ConnectionValuationDto v = service.connectionValuation(
                connectionId, List.of(bot(a, "10000", "WITHDRAW")));

        // Не прячем: боту роздано больше, чем есть на счёте, и это надо видеть.
        assertThat(v.unallocatedCash()).isEqualByComparingTo("-7000");
    }

    @Test
    void aBotWithoutBudgetMarksTheSumIncomplete() {
        UUID a = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        when(accounting.summaryFast(eq(a), anyBoolean())).thenReturn(stateOf("0", 0, "0"));
        when(accounting.summaryFast(eq(legacy), anyBoolean())).thenReturn(stateOf("0", 0, "250"));
        when(accountCash.available(any(), eq("RUB"))).thenReturn(new BigDecimal("12000"));

        ConnectionValuationDto v = service.connectionValuation(
                connectionId, List.of(bot(a, "10000", "WITHDRAW"), legacyBot(legacy)));

        assertThat(v.botCount()).isEqualTo(2);
        assertThat(v.valuedBotCount()).isEqualTo(1);
        assertThat(v.incomplete()).isTrue();
        assertThat(v.botsBalance()).isEqualByComparingTo("10000");
        // P/L считаем по всем ботам: он известен даже без бюджета.
        assertThat(v.botsPnl()).isEqualByComparingTo("250");
    }

    @Test
    void withoutAccountCashTheAggregateStaysHonestlyEmpty() {
        UUID a = UUID.randomUUID();
        when(accounting.summaryFast(eq(a), anyBoolean())).thenReturn(stateOf("0", 0, "0"));
        when(accountCash.available(any(), any())).thenReturn(null);

        ConnectionValuationDto v = service.connectionValuation(
                connectionId, List.of(bot(a, "10000", "WITHDRAW")));

        assertThat(v.botsBalance()).isEqualByComparingTo("10000");
        // Остаток портфеля неизвестен — итог не выдумываем.
        assertThat(v.unallocatedCash()).isNull();
        assertThat(v.total()).isNull();
    }

    @Test
    void connectionWithoutBotsReportsOnlyItsCash() {
        when(accountCash.available(any(), eq("RUB"))).thenReturn(new BigDecimal("7000"));

        ConnectionValuationDto v = service.connectionValuation(connectionId, List.of());

        assertThat(v.botCount()).isZero();
        assertThat(v.total()).isEqualByComparingTo("7000");
        assertThat(v.unallocatedCash()).isEqualByComparingTo("7000");
    }
}
