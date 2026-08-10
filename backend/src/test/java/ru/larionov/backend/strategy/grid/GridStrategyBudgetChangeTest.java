package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.CommandRequest;
import ru.larionov.backend.strategy.StrategyCommand;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Изменение бюджета на ходу: доливка денег и их вывод.
 *
 * Раньше бюджет менялся только через общую форму настроек, а она требует остановленного
 * бота — то есть снятия ВСЕХ заявок, включая встречные продажи незакрытых циклов. Ради
 * доливки рвать работающие циклы незачем, поэтому бюджет меняется командой.
 *
 * Лесенка здесь ручная и круглая (90..110, 4 уровня, шаг 5), чтобы числа читались:
 * уровни покупки 0..3 — это 90, 95, 100 и 105, а размер в UNIFORM равен
 * бюджет / (90+95+100+105) = бюджет / 390.
 */
class GridStrategyBudgetChangeTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-budget", null);
    private final Instant now = Instant.parse("2026-08-10T10:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<GridStrategyState> saved;
    private List<BotOrderView> openOrders;
    private List<PlaceIntent> placed;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        saved = new AtomicReference<>();
        openOrders = new ArrayList<>();
        placed = new ArrayList<>();

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.botId()).thenReturn(botId);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.loadState(GridStrategyState.class)).thenAnswer(__ -> Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(ctx).saveState(any());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) ->
                new FeeInfo(new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(exchange.calendar()).thenThrow(new UnsupportedOperationException("calendar disabled"));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));

        when(gateway.openOrders(botId)).thenAnswer(__ -> List.copyOf(openOrders));
        when(gateway.levelOrders(eq(botId), any())).thenAnswer(__ -> List.of());
        when(gateway.reconcile(any())).thenAnswer(__ -> reconciled());
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            placed.add(intent);
            BotOrderView order = view(intent.side(), intent.gridLevel(),
                    intent.limitPrice(), intent.quantity(), BigDecimal.ZERO);
            openOrders.add(order);
            return order;
        });
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(1);
            openOrders.removeIf(o -> o.id().equals(id));
            return null;
        }).when(gateway).cancel(any(), any());
    }

    /**
     * Доливка: заявки прежнего размера снимаются и тут же ставятся новым.
     *
     * Ждать их исполнения нельзя — покупка внизу диапазона может простоять неделю,
     * и всё это время долитые деньги не работали бы.
     */
    @Test
    void addedMoneyResizesOrdersThatHaveNotStartedFilling() {
        GridStrategy strategy = start(config("3900"));
        assertThat(placedBuys()).as("сетка расставлена исходным размером")
                .isNotEmpty().allMatch(i -> i.quantity().compareTo(BigDecimal.TEN) == 0);
        placed.clear();

        strategy.onCommand(new CommandRequest(StrategyCommand.SET_BUDGET, new BigDecimal("7800")));

        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("7800");
        assertThat(placedBuys())
                .as("бюджет удвоился — удвоился и размер заявки")
                .isNotEmpty().allMatch(i -> i.quantity().compareTo(new BigDecimal("20")) == 0);
        assertThat(openOrders).as("старых заявок на бирже не осталось")
                .allMatch(o -> o.requestedQuantity().compareTo(new BigDecimal("20")) == 0);
    }

    /** Вывод денег — та же операция в другую сторону. */
    @Test
    void withdrawnMoneyShrinksOrders() {
        GridStrategy strategy = start(config("7800"));
        placed.clear();

        strategy.onCommand(new CommandRequest(StrategyCommand.SET_BUDGET, new BigDecimal("3900")));

        assertThat(placedBuys()).isNotEmpty()
                .allMatch(i -> i.quantity().compareTo(BigDecimal.TEN) == 0);
    }

    /**
     * Начатый цикл трогать нельзя.
     *
     * Снятие частично исполненной покупки оставило бы на уровне позицию размером
     * «сколько успело», и закрывать её пришлось бы продажей другого объёма — то есть
     * смена бюджета молча меняла бы уже начатую сделку. Такой уровень доживает
     * прежним размером и переразмерится, когда его цикл закроется.
     */
    @Test
    void partiallyFilledBuyIsLeftAlone() {
        GridStrategy strategy = start(config("3900"));
        openOrders.clear();
        BotOrderView started = view(OrderSide.BUY, 1, new BigDecimal("95"),
                BigDecimal.TEN, new BigDecimal("4"));
        openOrders.add(started);
        placed.clear();

        strategy.onCommand(new CommandRequest(StrategyCommand.SET_BUDGET, new BigDecimal("7800")));

        assertThat(openOrders).as("начатая покупка осталась на бирже").contains(started);
        verify(gateway, never()).cancel(any(), eq(started.id()));
    }

    /**
     * Отвергнутый бюджет не должен оставлять бота в полуприменённом состоянии: сетка
     * продолжает торговать прежним размером, а оператор получает причину отказа.
     */
    @Test
    void rejectedBudgetLeavesTheGridUntouched() {
        GridStrategy strategy = start(config("3900"));
        placed.clear();

        assertThatThrownBy(() -> strategy.onCommand(
                new CommandRequest(StrategyCommand.SET_BUDGET, BigDecimal.TEN)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Бюджета");

        assertThat(strategy.snapshot().orElseThrow().workingBudget()).isEqualByComparingTo("3900");
        assertThat(placed).as("ни одной новой заявки после отказа").isEmpty();
        assertThat(openOrders).allMatch(o -> o.requestedQuantity().compareTo(BigDecimal.TEN) == 0);
    }

    /** У бота с фиксированным размером бюджета нет — команду он принимать не должен. */
    @Test
    void fixedQuantityBotDoesNotAcceptTheCommand() {
        GridStrategy budgeted = new GridStrategy(config("3900"));
        GridStrategy fixed = new GridStrategy(new GridConfig(
                new BigDecimal("90"), new BigDecimal("110"), 4, new BigDecimal("5"), 10,
                null, null, true));

        assertThat(budgeted.supports(StrategyCommand.SET_BUDGET)).isTrue();
        assertThat(fixed.supports(StrategyCommand.SET_BUDGET)).isFalse();
    }

    // ==============================
    // HARNESS
    // ==============================

    private GridStrategy start(GridConfig config) {
        GridStrategy strategy = new GridStrategy(config);
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        strategy.onPrice(price("100"));
        return strategy;
    }

    private GridConfig config(String budget) {
        return new GridConfig(
                new BigDecimal("90"), new BigDecimal("110"), 4, null, 10,
                null, null, null, true, false, null, null, null, null, null,
                null, null, null, null, null, null,
                new BigDecimal(budget), GridConfig.SizingMode.UNIFORM,
                GridConfig.ProfitPolicy.WITHDRAW);
    }

    private List<PlaceIntent> placedBuys() {
        return placed.stream().filter(i -> i.side() == OrderSide.BUY).toList();
    }

    private BotOrderView view(OrderSide side, Integer level, BigDecimal price,
                              BigDecimal quantity, BigDecimal executed) {
        UUID id = UUID.randomUUID();
        return new BotOrderView(
                id, id.toString(), "exch-" + id,
                side, executed.signum() > 0 ? OrderStatus.PARTIALLY_FILLED : OrderStatus.NEW,
                level, OrderPurpose.GRID, quantity, executed,
                price, price, null, false, null, null, "rub", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.copyOf(openOrders), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }
}
