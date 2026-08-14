package ru.larionov.backend.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.BotOrderRepository;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Знаковые партии: короткая позиция в денежной книге.
 *
 * Главное здесь — тождество {@code realizedPnl = cashFlow + costBasisOpen}. Оно
 * держит весь учёт: себестоимость по определению гасит денежный эффект ещё не
 * закрытых сделок, поэтому реализованный результат остаётся деньгами ЗАКРЫТЫХ
 * оборотов и ничем больше. Сломай его — и P/L начнёт врать не при ошибке, а при
 * каждой открытой позиции, то есть постоянно и незаметно.
 */
@SpringBootTest
class AccountingSignedParcelsTest {

    @Autowired
    private AccountingService accounting;
    @Autowired
    private BotOrderRepository orderRepo;

    private final UUID botId = UUID.randomUUID();

    private BotExecutionContext ctx() {
        return new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"),
                new InstrumentId("uid-" + botId, null),
                true, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "RUB");
    }

    /** Лонг остался ровно таким, каким был: это и есть страховка от регресса. */
    @Test
    @DisplayName("лонг: открытие не даёт результата, закрытие даёт его целиком")
    void longCycleIsUnchanged() {
        BotExecutionContext ctx = ctx();
        fill(ctx, OrderSide.BUY, GridRole.OPEN, "10", "100", "1");

        var afterOpen = accounting.summary(botId, true);
        assertThat(afterOpen.realizedPnl()).isEqualByComparingTo("0");
        assertThat(afterOpen.openQuantity()).isEqualByComparingTo("10");
        assertThat(afterOpen.costBasisOpen()).isEqualByComparingTo("1001");
        assertIdentity();

        fill(ctx, OrderSide.SELL, GridRole.CLOSE, "10", "110", "1.1");

        var afterClose = accounting.summary(botId, true);
        assertThat(afterClose.openQuantity()).isEqualByComparingTo("0");
        // 1100 − 1.1 − 1001 = 97.9
        assertThat(afterClose.realizedPnl()).isEqualByComparingTo("97.9");
        assertIdentity();
    }

    /**
     * Шорт: продажа ОТКРЫВАЕТ позицию, покупка её закрывает.
     *
     * Себестоимость короткой партии отрицательна — это не «во сколько обошлось»,
     * а обязательство: столько придётся отдать, чтобы вернуть занятое.
     */
    @Test
    @DisplayName("шорт: продажа открывает, откуп закрывает, знаки сходятся")
    void shortCycleOpensOnSell() {
        BotExecutionContext ctx = ctx();
        fill(ctx, OrderSide.SELL, GridRole.OPEN, "10", "100", "1");

        var afterOpen = accounting.summary(botId, true);
        assertThat(afterOpen.openQuantity()).isEqualByComparingTo("-10");
        // Получили 1000 за вычетом комиссии 1; себестоимость — это минус денежный эффект.
        assertThat(afterOpen.costBasisOpen()).isEqualByComparingTo("-999");
        assertThat(afterOpen.realizedPnl()).isEqualByComparingTo("0");
        // Средняя цена входа остаётся положительной ценой: знак сокращается.
        assertThat(afterOpen.averageEntryPrice()).isEqualByComparingTo("99.9");
        assertIdentity();

        fill(ctx, OrderSide.BUY, GridRole.CLOSE, "10", "90", "0.9");

        var afterClose = accounting.summary(botId, true);
        assertThat(afterClose.openQuantity()).isEqualByComparingTo("0");
        // Продали за 999 нетто, откупили за 900 плюс комиссия 0.9 → 98.1
        assertThat(afterClose.realizedPnl()).isEqualByComparingTo("98.1");
        assertIdentity();
    }

    /** Падение цены для шорта — прибыль, рост — убыток. Знак результата не должен врать. */
    @Test
    @DisplayName("шорт против движения даёт убыток, а не прибыль")
    void shortLosesWhenPriceRises() {
        BotExecutionContext ctx = ctx();
        fill(ctx, OrderSide.SELL, GridRole.OPEN, "10", "100", "1");
        fill(ctx, OrderSide.BUY, GridRole.CLOSE, "10", "110", "1.1");

        var summary = accounting.summary(botId, true);
        // 999 − 1100 − 1.1 = −102.1
        assertThat(summary.realizedPnl()).isEqualByComparingTo("-102.1");
        assertIdentity();
    }

    /**
     * Сделка ЧЕРЕЗ ноль: закрыла всё, что было, и на остаток открыла другую сторону.
     *
     * Так выглядит переворот позиции — ровно то, что делает восстановительное плечо.
     * Отбрось книга остаток, и позиция просто исчезла бы из учёта, оставшись на бирже.
     */
    @Test
    @DisplayName("продажа больше позиции переворачивает её в шорт, а не теряет остаток")
    void sellingMoreThanHeldFlipsIntoShort() {
        BotExecutionContext ctx = ctx();
        fill(ctx, OrderSide.BUY, GridRole.OPEN, "10", "100", "1");
        // Продаём вчетверо больше купленного: 10 закрывают лонг, 30 открывают шорт.
        fill(ctx, OrderSide.SELL, GridRole.CLOSE, "40", "100", "4");

        var summary = accounting.summary(botId, true);
        assertThat(summary.openQuantity())
                .as("после переворота позиция обязана стать короткой на остаток")
                .isEqualByComparingTo("-30");
        assertThat(summary.costBasisOpen())
                .as("обязательство по короткой ноге отрицательно")
                .isNegative();
        assertIdentity();
    }

    /**
     * Плечо и сетка ведут раздельный учёт партий.
     *
     * Это следствие одновременного режима: у заявок плеча нет уровня, а «без уровня»
     * в отборе означает «любая партия». Не раздели их — закрытие плеча съело бы
     * себестоимость незакрытого цикла сетки, и оба итога оказались бы неверны молча.
     */
    @Test
    @DisplayName("закрытие плеча не съедает партии сетки")
    void hedgeDoesNotEatGridParcels() {
        BotExecutionContext ctx = ctx();
        // Сетка купила на уровне 1 и цикл ещё не закрыла.
        fill(ctx, OrderSide.BUY, GridRole.OPEN, OrderPurpose.GRID, 1, "10", "100", "1");
        // Плечо открыло свою короткую позицию — без уровня, своим назначением.
        fill(ctx, OrderSide.SELL, GridRole.OPEN, OrderPurpose.HEDGE, null, "30", "100", "3");

        var afterOpen = accounting.summary(botId, true);
        assertThat(afterOpen.openQuantity())
                .as("нетто: длинная десятка сетки минус короткая тридцатка плеча")
                .isEqualByComparingTo("-20");
        assertIdentity();

        // Плечо закрывается целиком.
        fill(ctx, OrderSide.BUY, GridRole.CLOSE, OrderPurpose.RECOVERY, null, "30", "90", "2.7");

        var afterClose = accounting.summary(botId, true);
        assertThat(afterClose.openQuantity())
                .as("позиция сетки обязана уцелеть: её цикл никто не закрывал")
                .isEqualByComparingTo("10");
        assertThat(afterClose.costBasisOpen())
                .as("и её себестоимость тоже — плечо к ней не притрагивалось")
                .isEqualByComparingTo("1001");
        assertIdentity();
    }

    /** Тождество обязано держаться на КАЖДОМ шаге, а не только в конце. */
    private void assertIdentity() {
        var s = accounting.summary(botId, true);
        assertThat(s.realizedPnl())
                .as("realizedPnl = cashFlow + costBasisOpen")
                .isEqualByComparingTo(s.cashFlow().add(s.costBasisOpen()));
    }

    private void fill(BotExecutionContext ctx, OrderSide side, GridRole role,
                      String quantity, String price, String fee) {
        fill(ctx, side, role, OrderPurpose.GRID, 1, quantity, price, fee);
    }

    private void fill(BotExecutionContext ctx, OrderSide side, GridRole role, OrderPurpose purpose,
                      Integer level, String quantity, String price, String fee) {
        BotOrderEntity order = orderRepo.save(BotOrderEntity.builder()
                .botId(botId)
                .connectionId(ctx.connectionId())
                .accountId("acc")
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(UUID.randomUUID().toString())
                .side(side)
                .gridRole(role)
                .purpose(purpose)
                .status(OrderStatus.FILLED)
                .gridLevel(level)
                .requestedQuantity(new BigDecimal(quantity))
                .executedQuantity(new BigDecimal(quantity))
                .limitPrice(new BigDecimal(price))
                .avgPrice(new BigDecimal(price))
                .fee(new BigDecimal(fee))
                .feeActual(true)
                .feeCurrency("RUB")
                .exchangeLotSize(BigDecimal.ONE)
                .dryRun(true)
                .build());
        accounting.recordOrderState(ctx, order);
    }
}
