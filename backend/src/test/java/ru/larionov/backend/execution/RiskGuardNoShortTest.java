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
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.TradingSwitch;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

/**
 * Запрет шорта — предохранитель, который обязан пережить любую ошибку стратегии.
 *
 * Регрессия на реальный случай: стратегия недосчитала уже выставленные продажи и
 * раз за разом добавляла новые на позицию, которой не было. Остановил её тогда
 * только лимит частоты постановок, то есть случайность, а не осознанная защита.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskGuardNoShortTest {

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
    }

    /** Лимиты намеренно не заданы: инвариант «не шортим» не должен зависеть от настроек. */
    private static BotExecutionContext ctx() {
        return new BotExecutionContext(
                BOT, UUID.randomUUID(),
                new AccountId("acc-1"), new InstrumentId("uid-1", null),
                false, 1,
                null, null, null, null);
    }

    private static BotExecutionContext ctxLot10(BigDecimal maxCapital) {
        return new BotExecutionContext(
                BOT, UUID.randomUUID(),
                new AccountId("acc-1"), new InstrumentId("uid-1", null),
                false, 10,
                maxCapital, null, null, null);
    }

    private static BotOrderEntity openOrder(OrderSide side, long requested, long executed) {
        return BotOrderEntity.builder()
                .id(UUID.randomUUID())
                .botId(BOT)
                .side(side)
                .status(OrderStatus.NEW)
                .requestedLots(requested)
                .executedLots(executed)
                .limitPrice(new BigDecimal("22.2"))
                .dryRun(false)
                .build();
    }

    private static PlaceIntent sell(long lots) {
        return new PlaceIntent(OrderSide.SELL, lots, new BigDecimal("22.2"), 6);
    }

    @Test
    void sellIsRejectedWhenNothingWasBought() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(0L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> guard.check(ctx(), sell(1)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("Шорт не предусмотрен");
    }

    @Test
    void sellWithinPositionIsAllowed() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(4L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThatCode(() -> guard.check(ctx(), sell(1))).doesNotThrowAnyException();
    }

    /**
     * Главный случай: позиция есть, но она уже целиком выставлена на продажу.
     * Именно здесь старый код добавлял ещё одну заявку — и уходил в минус по позиции.
     */
    @Test
    void sellIsRejectedWhenPositionIsAlreadyFullyOffered() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(4L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of(
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0)));

        assertThatThrownBy(() -> guard.check(ctx(), sell(1)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("в заявках уже 4");
    }

    /** Частично исполненная продажа занимает только остаток — иначе бот застрянет. */
    @Test
    void partiallyFilledSellFreesItsExecutedPart() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(2L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of(
                openOrder(OrderSide.SELL, 2, 1)));

        assertThatCode(() -> guard.check(ctx(), sell(1))).doesNotThrowAnyException();
    }

    /** Открытые покупки на продажу не влияют: они позицию только увеличат. */
    @Test
    void openBuysDoNotBlockSelling() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(1L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of(
                openOrder(OrderSide.BUY, 5, 0)));

        assertThatCode(() -> guard.check(ctx(), sell(1))).doesNotThrowAnyException();
    }

    @Test
    void buyingIsNotAffectedByTheShortGuard() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(0L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThatCode(() -> guard.check(ctx(),
                new PlaceIntent(OrderSide.BUY, 1, new BigDecimal("21.5"), 0)))
                .doesNotThrowAnyException();
    }

    @Test
    void capitalLimitUsesLotSize() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(0L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> guard.check(ctxLot10(new BigDecimal("100")),
                new PlaceIntent(OrderSide.BUY, 1, new BigDecimal("22.1"), 0)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("221.0");
    }

    @Test
    void capitalLimitUsesLedgerCostBasisIncludingCommission() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(1L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(accounting.inventory(BOT, false))
                .thenReturn(new Inventory(1, new BigDecimal("95"), new BigDecimal("9.5"), 10));

        assertThatThrownBy(() -> guard.check(ctxLot10(new BigDecimal("100")),
                new PlaceIntent(OrderSide.BUY, 1, BigDecimal.ONE, 0)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("105");
    }

    @Test
    void capitalLimitRejectsBuyWhenLedgerDoesNotCoverJournalPosition() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(1L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> guard.check(ctxLot10(new BigDecimal("1000")),
                new PlaceIntent(OrderSide.BUY, 1, BigDecimal.ONE, 0)))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("не совпадает с денежной книгой");
    }

    /**
     * Воспроизводит журнал реального прогона: 5 купленных лотов, две продажи уже
     * выставлены, стратегия просит третью. Старый код её ставил — и так до упора
     * в лимит частоты.
     */
    @Test
    void reproducesRunawaySellsFromTheRealRun() {
        when(orderRepo.sumPositionLots(any(), anyBoolean())).thenReturn(5L);
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of(
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0),
                openOrder(OrderSide.SELL, 1, 0)));

        assertThatThrownBy(() -> guard.check(ctx(), sell(1)))
                .isInstanceOf(RiskRejectedException.class);

        assertThat(guard.usedCapital(ctx()))
                .as("продажи капитал не занимают")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
