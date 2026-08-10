package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
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
import static org.mockito.Mockito.*;

/**
 * Занятость уровня: купленное и выставленное на продажу нельзя докупать.
 *
 * Смысл сетки в том, что уровень живёт циклами. Купили на 50.00 — выставили встречную
 * продажу на 50.10 — и до её исполнения уровень 50.00 закрыт, сколько бы раз цена
 * туда ни возвращалась. Без этого откат к цене покупки докупает тот же уровень,
 * лоты подмешиваются к незакрытому циклу, и вместо одной встречной продажи на уровне
 * копится стопка, а позиция растёт вне плана сетки.
 *
 * Лесенка здесь ручная и круглая (50.00..50.50, шаг 0.10), чтобы уровни читались
 * глазами: 0 = 50.00, 1 = 50.10, 2 = 50.20.
 */
class GridStrategyLevelReuseTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-reuse", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<GridStrategyState> saved;
    private List<BotOrderView> openOrders;
    private List<BotOrderView> journal;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        saved = new AtomicReference<>();
        openOrders = new ArrayList<>();
        journal = new ArrayList<>();

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
        when(gateway.levelOrders(eq(botId), any())).thenAnswer(__ -> List.copyOf(journal));
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            BotOrderView order = view(intent.side(), intent.gridLevel(), intent.limitPrice(),
                    intent.quantity(), BigDecimal.ZERO, OrderStatus.NEW);
            openOrders.add(order);
            journal.add(order);
            return order;
        });
    }

    /**
     * Ровно сценарий из торгового дня: купили на уровне, выставили встречную продажу,
     * цена вернулась к цене покупки — и уровень не должен покупаться повторно.
     */
    @Test
    void levelWithPendingCounterSellIsNotBoughtAgainWhenPriceComesBack() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buyAtZero = openBuyAt(0);
        assertThat(buyAtZero).as("покупка на уровне 0 должна быть выставлена").isNotNull();

        fill(buyAtZero);
        strategy.onOrderUpdate(filled(buyAtZero));

        assertThat(openSellsCovering(0))
                .as("после исполнения покупки появляется встречная продажа на уровень выше")
                .hasSize(1);

        // Цена вернулась к цене покупки — и не один раз.
        strategy.onPrice(price("50.02"));
        strategy.onPrice(price("50.25"));
        strategy.onPrice(price("50.01"));

        assertThat(buysPlacedAt(0))
                .as("уровень занят непроданным циклом — повторной покупки быть не должно")
                .isEqualTo(1);
        assertThat(openSellsCovering(0))
                .as("и стопка встречных продаж на уровне копиться не должна")
                .hasSize(1);
    }

    /**
     * Обратная сторона: как только продажа исполнилась, цикл закрыт и уровень
     * обязан снова стать доступным — иначе сетка перестанет зарабатывать.
     */
    @Test
    void levelBecomesAvailableAgainOnceTheCounterSellIsFilled() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buyAtZero = openBuyAt(0);
        fill(buyAtZero);
        strategy.onOrderUpdate(filled(buyAtZero));

        BotOrderView counterSell = openSellsCovering(0).get(0);
        fill(counterSell);
        strategy.onOrderUpdate(filled(counterSell));

        strategy.onPrice(price("50.02"));

        assertThat(buysPlacedAt(0))
                .as("цикл закрыт — уровень снова работает")
                .isEqualTo(2);
    }

    // ==============================
    // HARNESS
    // ==============================

    /**
     * Инцидент 10.08.2026: позиция есть, а уровня, который за неё отвечает, нет.
     *
     * Продажи ставятся по уровневому учёту, а закрытия позиции ждут по позиции журнала.
     * Пока эти величины сходятся, разницы не видно; как только разошлись — на разницу
     * никто никогда не выставит заявку. Раньше расхождение не проверялось вовсе и
     * всплывало через сутки, на пробое диапазона, в виде вставшей перестановки.
     */
    @Test
    void positionNoLevelAnswersForIsReportedOnce() {
        GridStrategy strategy = start();
        // Журнал уровней пуст — ровно так выглядела обрезанная выборка, — а по позиции
        // за ботом числятся два лота.
        strategy.onReconcile(reconciled(new BigDecimal("2")));

        strategy.onPrice(price("50.25"));
        strategy.onPrice(price("50.24"));
        strategy.onPrice(price("50.26"));

        verify(ctx, times(1)).event(eq(BotEventType.RISK_BLOCKED),
                contains("не покрыта уровнями сетки"));
    }

    private GridStrategy start() {
        GridStrategy strategy = new GridStrategy(new GridConfig(
                new BigDecimal("50.00"), new BigDecimal("50.50"),
                5, new BigDecimal("2"), 10,
                GridConfig.RangeExitAction.STOP_BUYING, null, true));
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        return strategy;
    }

    /** Исполнение: заявка уходит из активных, а в журнале остаётся с исполненным объёмом. */
    private void fill(BotOrderView order) {
        openOrders.removeIf(o -> o.id().equals(order.id()));
        journal.replaceAll(o -> o.id().equals(order.id()) ? filled(order) : o);
    }

    private BotOrderView filled(BotOrderView order) {
        return view(order.side(), order.gridLevel(), order.limitPrice(),
                order.requestedQuantity(), order.requestedQuantity(), OrderStatus.FILLED, order.id());
    }

    private BotOrderView openBuyAt(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.BUY && Integer.valueOf(level).equals(o.gridLevel()))
                .findFirst()
                .orElse(null);
    }

    private List<BotOrderView> openSellsCovering(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.SELL && Integer.valueOf(level).equals(o.gridLevel()))
                .toList();
    }

    /** Сколько РАЗ бот пытался купить этот уровень за всё время теста. */
    private long buysPlacedAt(int level) {
        ArgumentCaptor<PlaceIntent> intents = ArgumentCaptor.forClass(PlaceIntent.class);
        verify(gateway, atLeast(0)).placeLimit(any(), intents.capture());
        return intents.getAllValues().stream()
                .filter(i -> i.side() == OrderSide.BUY && Integer.valueOf(level).equals(i.gridLevel()))
                .count();
    }

    private BotOrderView view(OrderSide side, Integer level, BigDecimal price,
                              long requested, long executed, OrderStatus status) {
        return view(side, level, price, BigDecimal.valueOf(requested), BigDecimal.valueOf(executed),
                status, UUID.randomUUID());
    }

    private BotOrderView view(OrderSide side, Integer level, BigDecimal price,
                              BigDecimal requested, BigDecimal executed, OrderStatus status) {
        return view(side, level, price, requested, executed, status, UUID.randomUUID());
    }

    private BotOrderView view(OrderSide side, Integer level, BigDecimal price,
                              BigDecimal requested, BigDecimal executed, OrderStatus status, UUID id) {
        return new BotOrderView(
                id, id.toString(), "exch-" + id,
                side, status, level, OrderPurpose.GRID, requested, executed,
                price, price, null, false, null, null, "rub", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
    }

    private ReconcileResult reconciled() {
        return reconciled(BigDecimal.ZERO);
    }

    /** Излишка на счёте нет — расхождение нулевое, чтобы торговлю ничто не блокировало. */
    private ReconcileResult reconciled(BigDecimal position) {
        return new ReconcileResult(List.copyOf(openOrders), position, position,
                BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }
}
