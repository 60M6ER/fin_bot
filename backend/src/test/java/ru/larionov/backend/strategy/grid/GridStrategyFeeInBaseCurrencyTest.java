package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Сетка на бирже, удерживающей комиссию ИЗ ПОЛУЧАЕМОЙ валюты.
 *
 * Poloniex берёт комиссию покупки монетой: заказали 10 DOGE — на баланс легло
 * 9.98. Отсюда следствие, ломающее сетку в самом её основании: запланированный
 * размер уровня НЕДОСТИЖИМ. Продавать «сколько купили» нельзя — столько монет
 * на счёте никогда не будет.
 *
 * Инцидент 07.08.2026: журнал записал покупку брутто, встречную продажу на тот же
 * объём биржа отбила как необеспеченную (21721 «available insufficient»), сверка
 * увидела расхождение ровно в комиссию и остановила торговлю.
 *
 * Лесенка круглая (50.00..50.50, шаг 0.10), чтобы уровни читались глазами.
 */
class GridStrategyFeeInBaseCurrencyTest {

    /** Комиссия монетой: 0.2% от купленного, как на боевом прогоне. */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.002");

    /** Шаг количества грубее комиссии — как у DOGE_USDT (0.001 против шести знаков). */
    private static final BigDecimal QUANTITY_STEP = new BigDecimal("0.001");

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-fee", null);
    private final Instant now = Instant.parse("2026-08-07T16:24:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<GridStrategyState> saved;
    private List<BotOrderView> openOrders;
    private List<BotOrderView> journal;
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
        journal = new ArrayList<>();
        placed = new ArrayList<>();

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(__ -> currentTime.get());
        when(ctx.clock()).thenReturn(clock);
        when(ctx.botId()).thenReturn(botId);
        when(ctx.gateway()).thenReturn(gateway);
        // Криптовые ограничения: заявочная единица — сама монета, дробить её можно
        // до 0.001. Целые лоты здесь не годятся — на них комиссия не даёт остатка.
        when(ctx.constraints()).thenReturn(new TradingConstraints(
                BigDecimal.ONE, QUANTITY_STEP, QUANTITY_STEP, null, new BigDecimal("0.01"), "usdt"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, QUANTITY_STEP, null, null, null, null, null));
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
        when(gateway.recentOrders(botId)).thenAnswer(__ -> List.copyOf(journal));
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            placed.add(intent);
            BotOrderView order = view(intent.side(), intent.gridLevel(), intent.limitPrice(),
                    intent.quantity(), BigDecimal.ZERO, OrderStatus.NEW, UUID.randomUUID());
            openOrders.add(order);
            journal.add(order);
            return order;
        });
    }

    /**
     * Главный случай инцидента: встречная продажа обязана уйти на то, что РЕАЛЬНО
     * лежит на счёте. Раньше её размер брался из плана сетки — на бирже такой
     * заявке нечем обеспечиться, и она отбивалась как необеспеченная.
     */
    @Test
    void counterSellOffersWhatIsHeldRatherThanWhatWasPlanned() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buy = openBuyAt(0);
        assertThat(buy).as("покупка на уровне 0 должна быть выставлена").isNotNull();
        fillBuyNetOfFee(buy);
        strategy.onOrderUpdate(journalRow(buy.id()));

        List<PlaceIntent> sells = sellsCovering(0);
        assertThat(sells).as("встречная продажа обязана появиться").hasSize(1);

        BigDecimal credited = buy.requestedQuantity()
                .subtract(buy.requestedQuantity().multiply(FEE_RATE));
        assertThat(sells.get(0).quantity())
                .as("продаём зачисленное, округлённое вниз до шага биржи")
                .isEqualByComparingTo(quantizeDown(credited));
        assertThat(sells.get(0).quantity())
                .as("на план сетки монет попросту нет")
                .isLessThan(buy.requestedQuantity());
    }

    /**
     * Пыль, оставшаяся от цикла, не должна выводить уровень из игры.
     *
     * Зачислено 140.544348, а продать при шаге 0.001 можно лишь 140.544: на уровне
     * навсегда оседает 0.000348. Продать этот остаток невозможно, а если считать
     * уровень занятым, сетка молча теряет его насовсем — продавать нечего, покупать
     * не даёт сама эта запись. И так после КАЖДОГО закрытого цикла.
     */
    @Test
    void dustLeftByAClosedCycleDoesNotFreezeTheLevel() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buy = openBuyAt(0);
        fillBuyNetOfFee(buy);
        strategy.onOrderUpdate(journalRow(buy.id()));

        BotOrderView sell = openSellCovering(0);
        assertThat(sell).isNotNull();
        fillFully(sell);
        strategy.onOrderUpdate(journalRow(sell.id()));

        // На уровне остался неторгуемый остаток: зачислено больше, чем удалось продать.
        BigDecimal dust = heldAtLevel(0);
        assertThat(dust).as("остаток есть, и он мельче шага").isGreaterThan(BigDecimal.ZERO);
        assertThat(dust).isLessThan(QUANTITY_STEP);

        strategy.onPrice(price("50.02"));

        assertThat(buysPlacedAt(0))
                .as("цикл закрыт — уровень обязан снова работать, пыль ему не помеха")
                .isEqualTo(2);
    }

    /**
     * Частично исполненную покупку продавать РАНО, пока она ещё работает.
     *
     * Это защита, которую легко потерять вместе с прежним условием «продавать, только
     * когда куплено не меньше плана»: оно заодно отсекало и недоисполненные покупки.
     * Продай мы исполненную часть сейчас — на остаток пришлась бы вторая продажа,
     * и вместо одной встречной заявки на уровне копилась бы стопка.
     *
     * Случай общий для всех бирж: у T-Invest частичные исполнения — обычное дело,
     * а комиссия там рублёвая и количество не трогает.
     */
    @Test
    void partiallyFilledBuyIsNotSoldWhileItIsStillWorking() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buy = openBuyAt(0);
        // Исполнилась половина, заявка ОСТАЁТСЯ в стакане.
        BigDecimal half = new BigDecimal("70.000");
        journal.replaceAll(o -> o.id().equals(buy.id())
                ? view(o.side(), o.gridLevel(), o.limitPrice(), o.requestedQuantity(),
                       half, OrderStatus.PARTIALLY_FILLED, o.id())
                : o);
        openOrders.replaceAll(o -> o.id().equals(buy.id())
                ? view(o.side(), o.gridLevel(), o.limitPrice(), o.requestedQuantity(),
                       half, OrderStatus.PARTIALLY_FILLED, o.id())
                : o);

        strategy.onPrice(price("50.30"));
        strategy.onPrice(price("50.25"));

        assertThat(sellsCovering(0))
                .as("покупка ещё в стакане — встречной продажи быть не должно")
                .isEmpty();
    }

    /** Остаток, который биржа не примет, не превращается в поток отвергаемых заявок. */
    @Test
    void untradableRemainderIsNotOfferedAtAll() {
        GridStrategy strategy = start();
        strategy.onPrice(price("50.25"));

        BotOrderView buy = openBuyAt(0);
        fillBuyNetOfFee(buy);
        strategy.onOrderUpdate(journalRow(buy.id()));

        BotOrderView sell = openSellCovering(0);
        fillFully(sell);
        strategy.onOrderUpdate(journalRow(sell.id()));

        int sellsAfterCycle = sellsCovering(0).size();
        strategy.onPrice(price("50.25"));
        strategy.onPrice(price("50.30"));

        assertThat(sellsCovering(0).size())
                .as("на пыль заявок не ставим — биржа их всё равно отвергнет")
                .isEqualTo(sellsAfterCycle);
    }

    // ==============================
    // HARNESS
    // ==============================

    private GridStrategy start() {
        GridStrategy strategy = new GridStrategy(new GridConfig(
                new BigDecimal("50.00"), new BigDecimal("50.50"),
                // Размер уровня — настоящий, из инцидента: 140.826 при комиссии 0.2%
                // даёт 140.544348 зачисленных, то есть остаток мельче шага 0.001.
                5, new BigDecimal("140.826"), 10,
                GridConfig.RangeExitAction.STOP_BUYING, null, true));
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        return strategy;
    }

    private static BigDecimal quantizeDown(BigDecimal quantity) {
        return quantity.divide(QUANTITY_STEP, 0, java.math.RoundingMode.DOWN).multiply(QUANTITY_STEP);
    }

    /** Покупка исполняется, но зачисляется МЕНЬШЕ на комиссию, взятую монетой. */
    private void fillBuyNetOfFee(BotOrderView order) {
        BigDecimal credited = order.requestedQuantity()
                .subtract(order.requestedQuantity().multiply(FEE_RATE));
        settle(order, credited);
    }

    /** Продажа уходит целиком: комиссия по ней берётся деньгами и количество не трогает. */
    private void fillFully(BotOrderView order) {
        settle(order, order.requestedQuantity());
    }

    private void settle(BotOrderView order, BigDecimal executed) {
        openOrders.removeIf(o -> o.id().equals(order.id()));
        journal.replaceAll(o -> o.id().equals(order.id())
                ? view(o.side(), o.gridLevel(), o.limitPrice(), o.requestedQuantity(),
                       executed, OrderStatus.FILLED, o.id())
                : o);
    }

    private BotOrderView journalRow(UUID id) {
        return journal.stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow();
    }

    /** Куплено минус продано на уровне — по журналу, как это делает сама стратегия. */
    private BigDecimal heldAtLevel(int level) {
        return journal.stream()
                .filter(o -> Integer.valueOf(level).equals(o.gridLevel()))
                .map(o -> o.side() == OrderSide.BUY ? o.executedQuantity() : o.executedQuantity().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BotOrderView openBuyAt(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.BUY && Integer.valueOf(level).equals(o.gridLevel()))
                .findFirst().orElse(null);
    }

    private BotOrderView openSellCovering(int level) {
        return openOrders.stream()
                .filter(o -> o.side() == OrderSide.SELL && Integer.valueOf(level).equals(o.gridLevel()))
                .findFirst().orElse(null);
    }

    private List<PlaceIntent> sellsCovering(int level) {
        return placed.stream()
                .filter(i -> i.side() == OrderSide.SELL && Integer.valueOf(level).equals(i.gridLevel()))
                .toList();
    }

    private long buysPlacedAt(int level) {
        return placed.stream()
                .filter(i -> i.side() == OrderSide.BUY && Integer.valueOf(level).equals(i.gridLevel()))
                .count();
    }

    private BotOrderView view(OrderSide side, Integer level, BigDecimal price,
                              BigDecimal requested, BigDecimal executed, OrderStatus status, UUID id) {
        return new BotOrderView(
                id, id.toString(), "exch-" + id,
                side, status, level, OrderPurpose.GRID, requested, executed,
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
