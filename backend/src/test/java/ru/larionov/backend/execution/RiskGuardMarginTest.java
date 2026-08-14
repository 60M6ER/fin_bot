package ru.larionov.backend.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
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
 * Границы короткой позиции — то, чем заменён прежний запрет шорта.
 *
 * Запрет никуда не делся: он проверяется в {@code RiskGuardNoShortTest} и работает,
 * пока боту не разрешена маржа. Здесь проверяется противоположная сторона — что
 * разрешение НЕ означает «сколько угодно». Убыток по длинной позиции ограничен снизу
 * нулём цены, по короткой сверху не ограничен ничем, и потолок здесь единственное,
 * что стоит между ботом и неограниченным риском.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RiskGuardMarginTest {

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
        when(orderRepo.findAllByBotIdAndStatusIn(any(), any())).thenReturn(List.of());
        // Позиции нет: любая продажа здесь открывает шорт.
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("шорт в пределах обоих потолков разрешён")
    void shortWithinBoundsPasses() {
        assertThatCode(() -> guard.check(ctx(true, true, "100", "10000"), sell("10")))
                .doesNotThrowAnyException();
    }

    /** Разрешение брокера по бумаге настройками бота не заменяется. */
    @Test
    @DisplayName("бумагу, которую брокер не шортит, не спасает никакая настройка")
    void instrumentGateWins() {
        assertThatThrownBy(() -> guard.check(ctx(true, false, "100", "10000"), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("не разрешает короткую позицию");
    }

    @Test
    @DisplayName("превышение потолка в штуках отвергается")
    void quantityCeilingRejects() {
        assertThatThrownBy(() -> guard.check(ctx(true, true, "5", "10000"), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("за потолок");
    }

    @Test
    @DisplayName("превышение денежного потолка отвергается")
    void notionalCeilingRejects() {
        // 10 × 22.2 = 222 при потолке 100.
        assertThatThrownBy(() -> guard.check(ctx(true, true, "100", "100"), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("денежный потолок");
    }

    /**
     * Забытый потолок означает запрет, а не свободу.
     *
     * Это отличает потолки шорта от остальных лимитов, где пустое поле значит
     * «без ограничения». Здесь так нельзя: неограниченным оказался бы не лимит,
     * а убыток.
     */
    @Test
    @DisplayName("незаданный потолок запрещает шорт, а не разрешает его")
    void missingCeilingsForbid() {
        assertThatThrownBy(() -> guard.check(ctx(true, true, null, "10000"), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("Не задан потолок короткой позиции в единицах");

        assertThatThrownBy(() -> guard.check(ctx(true, true, "100", null), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("Не задан потолок короткой позиции в деньгах");
    }

    /**
     * Продажа в пределах купленного — обычный цикл сетки, а не шорт.
     * Потолки к ней отношения не имеют и мешать ей не должны.
     */
    @Test
    @DisplayName("продажа купленного не считается шортом и потолками не ограничена")
    void sellingWhatWeOwnIsNotAShort() {
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("10"));

        assertThatCode(() -> guard.check(ctx(true, true, "1", "1"), sell("10")))
                .doesNotThrowAnyException();
    }

    /** Потолок считается по ИТОГОВОЙ короткой позиции, а не по размеру одной заявки. */
    @Test
    @DisplayName("потолок меряет всю позицию, а не отдельную заявку")
    void ceilingMeasuresWholePosition() {
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("-8"));

        assertThatCode(() -> guard.check(ctx(true, true, "10", "10000"), sell("2")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.check(ctx(true, true, "10", "10000"), sell("5")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("за потолок");
    }

    /**
     * Переворот позиции через ноль — операция осознанная, и совершить её может
     * только заявка с назначением «переворот».
     *
     * Обычная сетка продаёт то, что купила, и за ноль уходит только по ошибке:
     * недосчитала выставленные продажи, перепутала уровень, не увидела исполнения.
     * Пока шорт был запрещён целиком, от этого спасал сам запрет; теперь — эта проверка.
     */
    @Test
    @DisplayName("сетка не имеет права перевернуть длинную позицию в короткую")
    void gridMayNotFlipThroughZero() {
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("10"));

        assertThatThrownBy(() -> guard.check(ctx(true, true, "100", "10000"), sell("40")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("Через ноль позицию проводит только переворот");
    }

    @Test
    @DisplayName("перевороту это разрешено — ради него он и существует")
    void hedgeMayFlipThroughZero() {
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("10"));

        assertThatCode(() -> guard.check(ctx(true, true, "100", "10000"), hedge("40")))
                .doesNotThrowAnyException();
    }

    /** Углубление уже короткой позиции переворотом не является — потолки её и держат. */
    @Test
    @DisplayName("сетке можно углублять уже открытый шорт")
    void gridMayDeepenAnExistingShort() {
        when(orderRepo.sumPositionQuantity(any(), anyBoolean())).thenReturn(new BigDecimal("-5"));

        assertThatCode(() -> guard.check(ctx(true, true, "100", "10000"), sell("10")))
                .doesNotThrowAnyException();
    }

    /** Выключенная маржа возвращает прежний инвариант целиком. */
    @Test
    @DisplayName("без маржи действует прежний запрет шорта")
    void withoutMarginTheOldInvariantHolds() {
        assertThatThrownBy(() -> guard.check(ctx(false, true, "100", "10000"), sell("10")))
                .isInstanceOf(RiskRejectedException.class)
                .hasMessageContaining("Шорт не предусмотрен");
    }

    private static BotExecutionContext ctx(boolean marginEnabled, boolean shortEnabled,
                                           String maxShortQuantity, String maxShortNotional) {
        return new BotExecutionContext(
                BOT, UUID.randomUUID(), new AccountId("acc-1"), new InstrumentId("uid-1", null),
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                marginEnabled, shortEnabled,
                maxShortQuantity == null ? null : new BigDecimal(maxShortQuantity),
                maxShortNotional == null ? null : new BigDecimal(maxShortNotional));
    }

    private static PlaceIntent sell(String quantity) {
        return new PlaceIntent(OrderSide.SELL, new BigDecimal(quantity), new BigDecimal("22.2"),
                6, OrderPurpose.GRID, GridRole.OPEN);
    }

    /** Переворот: уровня у него нет, назначение отличает его от обслуживания сетки. */
    private static PlaceIntent hedge(String quantity) {
        return new PlaceIntent(OrderSide.SELL, new BigDecimal(quantity), new BigDecimal("22.2"),
                null, OrderPurpose.HEDGE, GridRole.OPEN);
    }
}
