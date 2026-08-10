package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.accounting.DustBucket;
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
 * Пыль как отдельная сущность: накопление, продажа и доливка.
 *
 * Там, где комиссия удерживается монетой, каждый закрытый цикл оставляет хвост
 * мельче шага количества. По отдельности хвост непродаваем навсегда, вместе —
 * вполне продаваем, и весь смысл в том, чтобы дождаться этого «вместе», не потеряв
 * себестоимость каждого куска.
 *
 * Лесенка круглая (50.00..50.50, шаг 0.10), чтобы уровни читались глазами.
 */
class GridStrategyDustTest {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.002");
    private static final BigDecimal QUANTITY_STEP = new BigDecimal("0.001");
    /** Минимальная сумма заявки: пыль дешевле неё биржа не примет. */
    private static final BigDecimal MIN_NOTIONAL = new BigDecimal("5");

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-dust", null);
    private final Instant now = Instant.parse("2026-08-09T10:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<DustBucket> bucket;
    private List<BotOrderView> openOrders;
    private List<BotOrderView> journal;
    private List<PlaceIntent> placed;
    private List<BigDecimal> recordedDust;
    private final java.util.Map<Integer, BigDecimal> collectedByLevel = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        bucket = new AtomicReference<>(DustBucket.empty());
        openOrders = new ArrayList<>();
        journal = new ArrayList<>();
        placed = new ArrayList<>();
        recordedDust = new ArrayList<>();
        collectedByLevel.clear();

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.botId()).thenReturn(botId);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(new TradingConstraints(
                BigDecimal.ONE, QUANTITY_STEP, QUANTITY_STEP, MIN_NOTIONAL,
                new BigDecimal("0.01"), "usdt"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, QUANTITY_STEP, MIN_NOTIONAL, null, null, null, null));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.empty());
        when(ctx.dust()).thenAnswer(__ -> bucket.get());
        // Книга помнит изъятое с уровня навсегда — иначе сборщик пыли брал бы
        // один и тот же хвост каждый проход и наращивал корзину из воздуха.
        when(ctx.dustByLevel()).thenAnswer(__ -> java.util.Map.copyOf(collectedByLevel));
        doAnswer(invocation -> {
            Integer level = invocation.getArgument(0);
            BigDecimal quantity = invocation.getArgument(1);
            recordedDust.add(quantity);
            collectedByLevel.merge(level, quantity, BigDecimal::add);
            return null;
        }).when(ctx).recordDust(any(), any());

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
            placed.add(intent);
            BotOrderView order = view(intent.side(), intent.gridLevel(), intent.purpose(),
                    intent.limitPrice(), intent.quantity(), BigDecimal.ZERO,
                    OrderStatus.NEW, UUID.randomUUID());
            openOrders.add(order);
            journal.add(order);
            return order;
        });
    }

    /**
     * Закрытый цикл оставляет непродаваемый хвост — он обязан уйти в пыль, а не
     * висеть на уровне. Уровень при этом освобождается под новую покупку.
     */
    @Test
    void closedCycleMovesItsUntradableRemainderIntoDust() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buy = openBuyAt(0);
        fillBuyNetOfFee(buy);
        strategy.onOrderUpdate(journalRow(buy.id()));

        BotOrderView sell = openSellCovering(0);
        fillFully(sell);
        strategy.onOrderUpdate(journalRow(sell.id()));

        strategy.onPrice(price("50.02"));

        assertThat(recordedDust)
                .as("хвост закрытого цикла обязан попасть в книгу как пыль")
                .hasSize(1);
        assertThat(recordedDust.get(0)).isLessThan(QUANTITY_STEP);

        // И повторный проход не должен собрать тот же хвост во второй раз.
        strategy.onPrice(price("50.03"));
        assertThat(recordedDust).hasSize(1);
    }

    /** Пока накопленного не хватает на заявку, которую биржа примет, — не торгуем. */
    @Test
    void dustBelowTheMinimumOrderIsNotOfferedAtAll() {
        bucket.set(new DustBucket(new BigDecimal("0.02"), new BigDecimal("1.00")));

        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        assertThat(dustSales()).isEmpty();
    }

    /**
     * Накопилось на заявку — продаём по себестоимости плюс комиссия оборота плюс
     * наценка. Ниже себестоимости пыль не отдаём: она не портится и подождёт.
     */
    @Test
    void accumulatedDustIsSoldAtCostPlusFeeAndMargin() {
        // 0.2 монеты, обошлись в 10 — по 50 за штуку. При цене 50 это 10 долларов,
        // то есть заявку такого размера биржа уже примет.
        bucket.set(new DustBucket(new BigDecimal("0.2"), new BigDecimal("10")));

        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        List<PlaceIntent> sales = dustSales();
        assertThat(sales).hasSize(1);
        assertThat(sales.get(0).quantity()).isEqualByComparingTo("0.2");
        // 50 × (1 + 0.001 комиссии оборота + 0.001 наценки) = 50.10
        assertThat(sales.get(0).limitPrice()).isEqualByComparingTo("50.10");
        assertThat(sales.get(0).gridLevel())
                .as("пыль не занимает уровня сетки")
                .isNull();
    }

    /**
     * Пыль пополнилась, пока заявка висела: старая снимается, новая уходит на всё
     * сразу и по пересчитанной цене — у нового хвоста своя себестоимость.
     */
    @Test
    void topUpReplacesTheOrderWithACombinedOneAtTheNewCost() {
        bucket.set(new DustBucket(new BigDecimal("0.2"), new BigDecimal("10")));
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView first = openDustOrder();
        assertThat(first).isNotNull();

        // Прибыла пыль подороже: 0.1 по 60. Средняя по корзине становится 100/3.
        bucket.set(new DustBucket(new BigDecimal("0.3"), new BigDecimal("16")));
        strategy.onPrice(price("50.30"));

        verify(gateway).cancel(any(), eq(first.id()));
        List<PlaceIntent> sales = dustSales();
        assertThat(sales).hasSize(2);
        assertThat(sales.get(1).quantity()).isEqualByComparingTo("0.3");
        // 16/0.3 = 53.333333333, ×1.002 = 53.44 после округления вверх до шага цены.
        assertThat(sales.get(1).limitPrice()).isEqualByComparingTo("53.44");
    }

    /** Ничего не изменилось — заявку не трогаем: снятие и постановка не бесплатны. */
    @Test
    void unchangedDustOrderIsLeftAlone() {
        bucket.set(new DustBucket(new BigDecimal("0.2"), new BigDecimal("10")));
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));
        strategy.onPrice(price("50.26"));
        strategy.onPrice(price("50.27"));

        assertThat(dustSales()).hasSize(1);
        verify(gateway, never()).cancel(any(), any());
    }

    // ==============================
    // HARNESS
    // ==============================

    private GridStrategy start() {
        GridStrategy strategy = new GridStrategy(new GridConfig(
                new BigDecimal("50.00"), new BigDecimal("50.50"),
                5, new BigDecimal("0.140"), 10,
                GridConfig.RangeExitAction.STOP_BUYING, null, true));
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        return strategy;
    }

    private List<PlaceIntent> dustSales() {
        return placed.stream().filter(i -> i.purpose() == OrderPurpose.DUST).toList();
    }

    private BotOrderView openDustOrder() {
        return openOrders.stream().filter(o -> o.purpose() == OrderPurpose.DUST)
                .findFirst().orElse(null);
    }

    private void fillBuyNetOfFee(BotOrderView order) {
        settle(order, order.requestedQuantity()
                .subtract(order.requestedQuantity().multiply(FEE_RATE)));
    }

    private void fillFully(BotOrderView order) {
        settle(order, order.requestedQuantity());
    }

    private void settle(BotOrderView order, BigDecimal executed) {
        openOrders.removeIf(o -> o.id().equals(order.id()));
        journal.replaceAll(o -> o.id().equals(order.id())
                ? view(o.side(), o.gridLevel(), o.purpose(), o.limitPrice(), o.requestedQuantity(),
                       executed, OrderStatus.FILLED, o.id())
                : o);
    }

    private BotOrderView journalRow(UUID id) {
        return journal.stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    private BotOrderView openBuyAt(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.BUY && Integer.valueOf(level).equals(o.gridLevel()))
                .findFirst().orElseThrow();
    }

    private BotOrderView openSellCovering(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.SELL && Integer.valueOf(level).equals(o.gridLevel()))
                .findFirst().orElseThrow();
    }

    private BotOrderView view(OrderSide side, Integer level, OrderPurpose purpose, BigDecimal price,
                              BigDecimal requested, BigDecimal executed, OrderStatus status, UUID id) {
        return new BotOrderView(
                id, id.toString(), "exch-" + id,
                side, status, level, purpose, requested, executed,
                price, price, null, false, null, null, "usdt", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.copyOf(openOrders), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "usdt"), currentTime.get());
    }
}
