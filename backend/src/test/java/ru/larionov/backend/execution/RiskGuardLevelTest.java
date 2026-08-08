package ru.larionov.backend.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.TradingSwitch;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Один уровень — одна заявка.
 *
 * Регрессия на 09.08.2026: бот на SOL/USDT выставил на нулевом уровне две покупки
 * подряд. Стратегия дважды сочла уровень свободным, и остановить её было нечему —
 * ни одна проверка не смотрела на то, занят ли уровень уже висящей заявкой.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskGuardLevelTest {

    private static final UUID BOT = UUID.randomUUID();

    @Mock
    private BotOrderRepository orderRepo;

    @Mock
    private TradingSwitch tradingSwitch;

    @Mock
    private AccountingService accounting;

    private RiskGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RiskGuard(orderRepo, tradingSwitch, accounting);
        when(tradingSwitch.isEnabled()).thenReturn(true);
        when(accounting.inventory(any(), anyBoolean())).thenReturn(Inventory.empty());
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("100"));
    }

    @Test
    void secondBuyOnTheSameLevelIsRefused() {
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any()))
                .thenReturn(List.of(openOrder(OrderSide.BUY, 0)));

        assertThatThrownBy(() -> guard.check(ctx(), buy(0)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("уровне 0");
    }

    /** Соседний уровень — другой цикл и другие деньги: он свободен. */
    @Test
    void aBuyOnAnotherLevelIsAllowed() {
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any()))
                .thenReturn(List.of(openOrder(OrderSide.BUY, 0)));

        assertThatCode(() -> guard.check(ctx(), buy(1))).doesNotThrowAnyException();
    }

    /** Встречная продажа закрывает тот же уровень и покупке не мешает. */
    @Test
    void aSellDoesNotBlockTheBuyOnTheSameLevel() {
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any()))
                .thenReturn(List.of(openOrder(OrderSide.SELL, 0)));

        assertThatCode(() -> guard.check(ctx(), buy(0))).doesNotThrowAnyException();
    }

    /**
     * А вот ПРОДАЖ на уровне законно бывает несколько: уровень мог набираться
     * частями, и каждая закрывается своей встречной заявкой. Запрет на них означал
     * бы, что вторая часть уровня не продастся никогда.
     */
    @Test
    void severalSellsOnOneLevelRemainAllowed() {
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any()))
                .thenReturn(List.of(openOrder(OrderSide.SELL, 0)));

        PlaceIntent secondSell = new PlaceIntent(OrderSide.SELL, new BigDecimal("0.5"),
                new BigDecimal("74.218"), 0);

        assertThatCode(() -> guard.check(ctx(), secondSell)).doesNotThrowAnyException();
    }

    /**
     * У заявок вне сетки уровня нет вовсе — ни у ликвидации, ни у продажи пыли,
     * и «занятость уровня» к ним неприменима.
     */
    @Test
    void ordersWithoutALevelAreNotSubjectToTheRule() {
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any()))
                .thenReturn(List.of(openOrder(OrderSide.SELL, null)));

        PlaceIntent dust = new PlaceIntent(OrderSide.SELL, new BigDecimal("0.2"),
                new BigDecimal("50"), null, OrderPurpose.DUST);

        assertThatCode(() -> guard.check(ctx(), dust)).doesNotThrowAnyException();
    }

    private static BotOrderEntity openOrder(OrderSide side, Integer level) {
        return BotOrderEntity.builder()
                .id(UUID.randomUUID())
                .botId(BOT)
                .side(side)
                .status(OrderStatus.NEW)
                .gridLevel(level)
                .requestedQuantity(new BigDecimal("1"))
                .executedQuantity(BigDecimal.ZERO)
                .limitPrice(new BigDecimal("73.731"))
                .dryRun(false)
                .build();
    }

    private static PlaceIntent buy(int level) {
        return new PlaceIntent(OrderSide.BUY, new BigDecimal("0.088238"),
                new BigDecimal("73.731"), level);
    }

    private static BotExecutionContext ctx() {
        return new BotExecutionContext(
                BOT, UUID.randomUUID(),
                new AccountId("acc-1"), new InstrumentId("uid-1", null),
                false, BigDecimal.ONE, new BigDecimal("0.000001"), null,
                null, null, null, null);
    }
}
