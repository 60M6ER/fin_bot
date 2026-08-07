package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.Candle;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Остановка бота во время обработки события.
 *
 * Регрессия на реальную ошибку с боевого сервера при перезапуске:
 * {@code Cannot invoke "StrategyContext.clock()" because "this.ctx" is null} —
 * onStop() выполнялся на управляющем потоке и отбирал состояние ровно тогда, когда
 * рабочий поток цикла уже прошёл проверку готовности и работал с этим состоянием.
 */
class GridStrategyStopRaceTest {

    private final UUID botId = UUID.randomUUID();
    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    private StrategyContext ctx;
    private ExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.empty());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(exchange.calendar()).thenThrow(new UnsupportedOperationException("calendar disabled in unit test"));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId))
                .thenReturn(new LastPrice(instrumentId, new Price(new BigDecimal("100"), "rub"), now));
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(gateway.openOrders(botId)).thenReturn(List.of());
        when(gateway.recentOrders(botId)).thenReturn(List.of());
        when(gateway.reconcile(any())).thenReturn(reconciled());
    }

    /**
     * Останавливаем стратегию ровно в тот момент, когда она внутри onTick.
     *
     * До починки этот тест падал с NPE: проверка готовности успевала пройти,
     * а onStop отбирал ctx до следующего к нему обращения.
     */
    @Test
    void stoppingWhileAnEventIsBeingHandledDoesNotBlowUp() throws Exception {
        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled());

        CountDownLatch insideTick = new CountDownLatch(1);
        CountDownLatch stopDone = new CountDownLatch(1);

        // Подвешиваем поток обработки внутри onTick: сверка — первое, что он делает.
        when(gateway.reconcile(any())).thenAnswer(invocation -> {
            insideTick.countDown();
            // Ждём, пока управляющий поток объявит остановку, и продолжаем работать.
            stopDone.await(3, TimeUnit.SECONDS);
            return reconciled();
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                strategy.onTick();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "loop-worker");
        worker.start();

        assertThat(insideTick.await(3, TimeUnit.SECONDS))
                .as("обработка события должна была начаться").isTrue();

        strategy.onStop();
        stopDone.countDown();

        worker.join(5_000);
        assertThat(failure.get())
                .as("остановка не должна ронять обработку идущего события")
                .isNull();
    }

    /** После остановки события молча игнорируются, а не выполняются наполовину. */
    @Test
    void eventsArrivingAfterTheStopAreIgnored() {
        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled());
        strategy.onStop();

        strategy.onTick();
        strategy.onPrice(new LastPrice(instrumentId, new Price(new BigDecimal("100"), "rub"), now));
        strategy.onStreamReconnect();
        strategy.onTradingStatus(new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));

        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).placeLimit(any(), any());
        assertThat(strategy.snapshot()).isEmpty();
    }

    private GridConfig autoConfig() {
        return new GridConfig(
                null, null, 4, new BigDecimal("1"), 4, null, null, null, true,
                true, null, 6, new BigDecimal("2"),
                new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 0, null,
                null, null, null);
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO);
    }

    private List<Candle> candles() {
        return java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new Candle(instrumentId,
                        new Price(new BigDecimal("100"), "rub"),
                        new Price(new BigDecimal("102"), "rub"),
                        new Price(new BigDecimal("98"), "rub"),
                        new Price(new BigDecimal("100"), "rub"),
                        BigDecimal.ONE, now.minusSeconds((6L - i) * 3600), null))
                .toList();
    }
}
