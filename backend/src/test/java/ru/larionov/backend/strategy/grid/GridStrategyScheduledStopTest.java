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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Плановая остановка: снять покупки и дождаться, пока распродастся позиция.
 *
 * Обычная остановка гасит бота вместе с купленным, и разбираться с позицией
 * приходится руками — а удалить такого бота нельзя вовсе, пока на бирже висят
 * его заявки. Плановая делает то же самое в правильном порядке.
 */
class GridStrategyScheduledStopTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-stop", null);
    private final Instant now = Instant.parse("2026-08-09T10:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<BigDecimal> position;
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
        position = new AtomicReference<>(BigDecimal.ZERO);
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
        when(gateway.recentOrders(botId)).thenAnswer(__ -> List.of());
        when(gateway.reconcile(any())).thenAnswer(__ -> reconciled());
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            placed.add(intent);
            BotOrderView order = view(intent.side(), intent.gridLevel(), intent.purpose(),
                    intent.limitPrice(), intent.quantity());
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
     * Главное: покупки снимаются сразу, продажи остаются работать. Именно они
     * и закрывают позицию — каждая по своей цене уровня.
     */
    @Test
    void scheduledStopCancelsBuysAndKeepsSellsWorking() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));

        assertThat(openBuys()).as("сетка обязана была выставить покупки").isNotEmpty();
        BotOrderView sell = placeSellManually();

        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);

        assertThat(openBuys()).as("покупки сняты — это и есть не начавшие исполняться заявки").isEmpty();
        assertThat(openOrders).as("продажа осталась работать").contains(sell);
        assertThat(saved.get().stopScheduled()).isTrue();
        assertThat(strategy.snapshot().orElseThrow().stopScheduled()).isTrue();
    }

    /** Новых покупок после плановой остановки быть не должно, сколько бы цена ни ходила. */
    @Test
    void noNewBuysAppearWhileWaiting() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));
        placeSellManually();

        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);
        placed.clear();

        strategy.onPrice(price("95"));
        strategy.onPrice(price("105"));
        strategy.onTick();

        assertThat(placed).filteredOn(i -> i.side() == OrderSide.BUY)
                .as("бот уходит из позиции, а не набирает её заново")
                .isEmpty();
    }

    /**
     * Позиция закрылась — бот выключается сам, предварительно сняв всё, что осталось
     * на бирже. Иначе его нельзя было бы удалить: удалению мешает любая живая заявка.
     */
    @Test
    void theBotStopsItselfOnceThePositionIsClosed() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));
        placeSellManually();
        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);
        verify(ctx, never()).requestStop(any());

        // Продажа исполнилась: позиции больше нет.
        position.set(BigDecimal.ZERO);
        openOrders.clear();
        strategy.onTick();

        verify(ctx).requestStop(org.mockito.ArgumentMatchers.contains("Плановая остановка"));
    }

    /** Пока позиция не распродана, бот не выключается — иначе смысл теряется. */
    @Test
    void theBotKeepsWaitingWhileThePositionIsStillThere() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));
        placeSellManually();

        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);
        strategy.onTick();
        strategy.onTick();

        verify(ctx, never()).requestStop(any());
    }

    /** Решение отменяемо: иначе плановая остановка была бы ловушкой. */
    @Test
    void theScheduledStopCanBeCancelled() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));
        placeSellManually();
        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);

        strategy.onCommand(StrategyCommand.CANCEL_SCHEDULED_STOP);

        assertThat(saved.get().stopScheduled()).isFalse();
        assertThat(strategy.snapshot().orElseThrow().stopScheduled()).isFalse();
        assertThat(openBuys()).as("сетка вернулась к работе").isNotEmpty();
    }

    /**
     * Решение переживает рестарт. Иначе поднятый супервизором бот забыл бы о нём
     * и снова начал покупать — а владелец в это время ждёт, когда можно удалять.
     */
    @Test
    void theDecisionSurvivesARestart() {
        position.set(new BigDecimal("5"));
        GridStrategy strategy = start();
        strategy.onPrice(price("100"));
        placeSellManually();
        strategy.onCommand(StrategyCommand.SCHEDULE_STOP);

        GridStrategy restarted = start();
        restarted.onPrice(price("100"));

        assertThat(restarted.snapshot().orElseThrow().stopScheduled()).isTrue();
        assertThat(openBuys()).as("после рестарта покупок тоже быть не должно").isEmpty();
    }

    // ==============================
    // HARNESS
    // ==============================

    private GridStrategy start() {
        GridStrategy strategy = new GridStrategy(new GridConfig(
                new BigDecimal("90"), new BigDecimal("110"),
                4, BigDecimal.ONE, 10,
                GridConfig.RangeExitAction.STOP_BUYING, null, true));
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        return strategy;
    }

    /** Встречная продажа: в этих сценариях важно, что она есть, а не как появилась. */
    private BotOrderView placeSellManually() {
        BotOrderView sell = view(OrderSide.SELL, 2, OrderPurpose.GRID,
                new BigDecimal("105"), BigDecimal.ONE);
        openOrders.add(sell);
        return sell;
    }

    private List<BotOrderView> openBuys() {
        return openOrders.stream().filter(o -> o.side() == OrderSide.BUY).toList();
    }

    private BotOrderView view(OrderSide side, Integer level, OrderPurpose purpose,
                              BigDecimal price, BigDecimal quantity) {
        UUID id = UUID.randomUUID();
        return new BotOrderView(
                id, id.toString(), "exch-" + id,
                side, OrderStatus.NEW, level, purpose, quantity, BigDecimal.ZERO,
                price, price, null, false, null, null, "rub", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.copyOf(openOrders), position.get(), position.get(),
                BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }
}
