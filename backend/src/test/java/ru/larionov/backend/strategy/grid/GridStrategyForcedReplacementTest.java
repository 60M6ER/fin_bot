package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.CandleInterval;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.OrderBookLevel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Ручная перестановка сетки — выход из тупика, в который бот попадает штатно:
 * бюджет убытка исчерпан, цена ниже всего диапазона, купить нельзя (уровни выше
 * рынка), продать нельзя (позиция дороже). Бот работает и не делает ничего.
 */
class GridStrategyForcedReplacementTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-forced", null);
    private final Instant now = Instant.parse("2026-02-10T12:00:00Z");

    private StrategyContext ctx;
    private MarketDataApi marketData;
    private ExecutionGateway gateway;
    private AtomicReference<Instant> currentTime;
    private AtomicReference<BigDecimal> position;
    private AtomicReference<BigDecimal> bid;
    private AtomicReference<BigDecimal> realizedPnl;
    private AtomicReference<Inventory> inventory;
    private AtomicReference<GridStrategyState> saved;
    private List<BotOrderView> openOrders;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);
        currentTime = new AtomicReference<>(now);
        position = new AtomicReference<>(BigDecimal.ZERO);
        bid = new AtomicReference<>(new BigDecimal("80"));
        realizedPnl = new AtomicReference<>(BigDecimal.ZERO);
        inventory = new AtomicReference<>(Inventory.empty());
        saved = new AtomicReference<>();
        openOrders = new ArrayList<>();

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
        when(ctx.inventory()).thenAnswer(__ -> inventory.get());
        when(ctx.realizedPnl()).thenAnswer(__ -> realizedPnl.get());
        when(ctx.loadState(GridStrategyState.class)).thenAnswer(__ -> Optional.ofNullable(saved.get()));
        doAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return null;
        }).when(ctx).saveState(any());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) ->
                new FeeInfo(new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", Instant.now()));
        when(marketData.getLastPrice(instrumentId)).thenAnswer(__ -> lastPrice("80"));
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(marketData.getOrderBook(instrumentId, 1)).thenAnswer(__ -> orderBook(bid.get()));

        when(gateway.openOrders(botId)).thenAnswer(__ -> List.copyOf(openOrders));
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.reconcile(any())).thenAnswer(__ -> reconciled(position.get()));
        doAnswer(__ -> {
            int count = openOrders.size();
            openOrders.clear();
            return count;
        }).when(gateway).cancelAll(any());
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(1);
            openOrders.removeIf(order -> order.id().equals(id));
            return null;
        }).when(gateway).cancel(any(), any());
        when(gateway.placeLimit(any(), any())).thenAnswer(invocation -> {
            PlaceIntent intent = invocation.getArgument(1);
            BotOrderView order = order(intent);
            openOrders.add(order);
            return order;
        });
    }

    /**
     * Тот самый бот с боевого: бюджет убытка выбран прошлыми перестановками,
     * позиция на руках, цена ниже диапазона. Автоматика бессильна — команда обязана
     * пройти.
     */
    @Test
    void deadlockedBotLiquidatesAndGetsFreshGridOnOperatorCommand() {
        GridStrategy strategy = deadlocked();

        strategy.onCommand(StrategyCommand.FORCE_GRID_REPLACEMENT);

        ArgumentCaptor<PlaceIntent> intent = ArgumentCaptor.forClass(PlaceIntent.class);
        verify(gateway, atLeastOnce()).placeLimit(any(), intent.capture());
        PlaceIntent liquidation = intent.getValue();
        assertThat(liquidation.side()).isEqualTo(OrderSide.SELL);
        assertThat(liquidation.quantity()).isEqualByComparingTo("10");
        assertThat(liquidation.limitPrice())
                .as("Закрытие идёт по лучшему биду, а не по цене старой сетки")
                .isEqualByComparingTo("80");
        assertThat(saved.get().forcedReplacement())
                .as("Разрешение оператора обязано пережить рестарт посреди ликвидации")
                .isTrue();

        // Позиция закрыта: убыток зафиксирован в книге, поколение переключается.
        openOrders.clear();
        position.set(BigDecimal.ZERO);
        inventory.set(Inventory.empty());
        realizedPnl.set(new BigDecimal("-250"));
        strategy.onReconcile(reconciled(BigDecimal.ZERO));

        assertThat(saved.get().generation()).isEqualTo(3);
        assertThat(saved.get().activeRange().origin()).isEqualTo(GridRange.Origin.ATR_REPLACED_DOWN);
        assertThat(saved.get().activeRange().lower())
                .as("Новая сетка строится вокруг текущей цены, а не вокруг старой")
                .isLessThan(new BigDecimal("92"));
        assertThat(saved.get().forcedReplacement()).isFalse();
        verify(ctx).event(eq(BotEventType.GRID_REPLACED), contains("по команде оператора"));
    }

    /** Счётчики риск-бюджета обнуляются — иначе кнопка была бы одноразовой. */
    @Test
    void riskBudgetStartsOverAfterForcedReplacement() {
        GridStrategy strategy = deadlocked();

        strategy.onCommand(StrategyCommand.FORCE_GRID_REPLACEMENT);
        openOrders.clear();
        position.set(BigDecimal.ZERO);
        inventory.set(Inventory.empty());
        realizedPnl.set(new BigDecimal("-250"));
        strategy.onReconcile(reconciled(BigDecimal.ZERO));

        assertThat(saved.get().realizedDownwardLoss()).isEqualByComparingTo("0");
        assertThat(saved.get().downwardReplacements()).isEqualTo(0);
        assertThat(strategy.snapshot().orElseThrow().halted()).isFalse();
    }

    /**
     * Расхождение позиции — единственная причина, по которой команда обязана
     * отказать: закрывать по рынку то, чего мы не знаем, нельзя ни по чьей команде.
     */
    @Test
    void refusesWhenLedgerPositionDisagreesWithExchange() {
        GridStrategy strategy = deadlocked();
        // Журнал считает за ботом 10, а на счёте лишь 7: недостача, торговать нельзя.
        ReconcileResult mismatched = new ReconcileResult(
                List.of(), new BigDecimal("10"), new BigDecimal("7"), BigDecimal.ZERO,
                0, 0, new BigDecimal("-3"));
        strategy.onReconcile(mismatched);
        strategy.onReconcile(mismatched);
        clearInvocations(gateway);

        assertThatThrownBy(() -> strategy.onCommand(StrategyCommand.FORCE_GRID_REPLACEMENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("расходится с биржей");

        verify(gateway, never()).placeLimit(any(), any());
    }

    /**
     * После рестарта цены из стрима ещё нет — а именно в этом состоянии кнопкой
     * и пользуются. Центр нового диапазона всё равно должен найтись.
     *
     * Раньше цену спрашивала сама команда. Теперь бот берёт её у биржи ещё на старте
     * (стрим последней цены присылает событие только при сделке, и без этого запроса
     * бот с восстановленным диапазоном стоял бы без единой заявки), поэтому к моменту
     * нажатия она уже известна. Проверяем не число запросов, а то, ради чего всё
     * затевалось: ликвидация уходит на биржу.
     */
    @Test
    void forcedReplacementWorksEvenWhenStreamHasNotDeliveredAPriceYet() {
        GridStrategy strategy = deadlocked();

        strategy.onCommand(StrategyCommand.FORCE_GRID_REPLACEMENT);

        verify(gateway, atLeastOnce()).placeLimit(any(), argThat(i -> i.side() == OrderSide.SELL));
    }

    /**
     * И отдельно — то, что теперь делает старт: цена берётся у биржи, если стрим
     * её ещё не принёс. Именно этого не хватало ботам T-Invest на боевом сервере
     * 07.08.2026 — они поднимались с восстановленным диапазоном и не торговали.
     */
    @Test
    void startupAsksExchangeForPriceWhenStreamHasNotDeliveredOneYet() {
        GridRange active = new GridRange(new BigDecimal("92"), new BigDecimal("108"), 4,
                GridRange.Origin.ATR_REPLACED_DOWN, now.minusSeconds(86400));
        saved.set(new GridStrategyState(active, 2, false, now.minusSeconds(7200),
                false, null, 2, new BigDecimal("100"), null));
        position.set(BigDecimal.TEN);
        inventory.set(new Inventory(BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("100")));

        new GridStrategy(config()).onStart(ctx, reconciled(position.get()));

        verify(marketData).getLastPrice(instrumentId);
    }

    /**
     * Бот в тупике: сохранённая сетка 92..108, позиция 10, лимит перестановок
     * исчерпан, накопленный убыток равен потолку. Цену стрим ещё не приносил.
     */
    private GridStrategy deadlocked() {
        GridRange active = new GridRange(new BigDecimal("92"), new BigDecimal("108"), 4,
                GridRange.Origin.ATR_REPLACED_DOWN, now.minusSeconds(86400));
        saved.set(new GridStrategyState(active, 2, false, now.minusSeconds(7200),
                false, null, 2, new BigDecimal("100"), null));
        position.set(BigDecimal.TEN);
        inventory.set(new Inventory(BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("100")));

        GridStrategy strategy = new GridStrategy(config());
        strategy.onStart(ctx, reconciled(position.get()));
        strategy.onReconcile(reconciled(position.get()));
        clearInvocations(gateway, marketData);
        return strategy;
    }

    private GridConfig config() {
        return new GridConfig(
                null, null, 4, new BigDecimal("1"), 4,
                GridConfig.RangeExitAction.REPLACE_LOWER, null, 3600, true,
                true, CandleInterval.H1, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                GridConfig.UpperBreakoutAction.NOTHING, 10, new BigDecimal("0.002"),
                1200, 2, new BigDecimal("100"),
                null, null, null);
    }

    private ReconcileResult reconciled(BigDecimal value) {
        return new ReconcileResult(List.of(), value, value, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice lastPrice(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), currentTime.get());
    }

    private OrderBook orderBook(BigDecimal value) {
        return new OrderBook(instrumentId, 1,
                List.of(new OrderBookLevel(new Price(value, "rub"), BigDecimal.ONE)),
                List.of(), null, null, currentTime.get());
    }

    private BotOrderView order(PlaceIntent intent) {
        return new BotOrderView(
                UUID.randomUUID(), UUID.randomUUID().toString(), null,
                intent.side(), ru.larionov.backend.exchange.api.enums.OrderStatus.NEW,
                intent.gridLevel(), intent.purpose(), intent.quantity(), BigDecimal.ZERO,
                intent.limitPrice(), null, null, false, null, null, "rub", BigDecimal.ONE,
                false, null, currentTime.get(), currentTime.get());
    }

    private List<Candle> candles() {
        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new Candle(instrumentId,
                        new Price(new BigDecimal("80"), "rub"),
                        new Price(new BigDecimal("82"), "rub"),
                        new Price(new BigDecimal("78"), "rub"),
                        new Price(new BigDecimal("80"), "rub"),
                        BigDecimal.ONE, now.minusSeconds((6L - i) * 3600), null))
                .toList();
    }
}
