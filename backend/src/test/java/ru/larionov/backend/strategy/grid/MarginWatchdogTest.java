package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.account.MarginAttributes;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Сторож обеспечения счёта.
 *
 * Проверяется ровно то, что он на этой фазе обязан делать, — говорить, — и ровно то,
 * чего обязан НЕ делать: трогать заявки. Второе существеннее первого: сторож, который
 * начнёт закрывать позиции по непроверенному порогу, опаснее того, что он предотвращает.
 */
class MarginWatchdogTest {

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
        when(ctx.marginAttributes()).thenReturn(Optional.empty());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId))
                .thenReturn(new LastPrice(instrumentId, new Price(new BigDecimal("100"), "rub"), now));
        when(marketData.getCandles(any(), any())).thenReturn(candles());
        when(gateway.openOrders(botId)).thenReturn(List.of());
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.reconcile(any())).thenReturn(reconciled());
    }

    @Test
    @DisplayName("здоровый счёт не порождает ни одного предупреждения")
    void healthyAccountIsSilent() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("19.43", "-12708.16")));

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, never()).event(any(BotEventLevel.class), eq(BotEventType.RISK_BLOCKED), any());
    }

    /**
     * Отрицательная «нехватка» — это профицит.
     *
     * Не выдумка: именно так брокер и отвечает на здоровом счёте. Проверка вида
     * «не ноль» дала бы здесь маржин-колл на ровном месте, поэтому случай закреплён
     * отдельным тестом.
     */
    @Test
    @DisplayName("отрицательная нехватка средств означает запас, а не маржин-колл")
    void negativeMissingFundsIsSurplus() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("5.0", "-1000")));

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, never()).event(any(BotEventLevel.class), eq(BotEventType.RISK_BLOCKED), any());
    }

    @Test
    @DisplayName("просевшая достаточность — предупреждение, но заявки не трогаются")
    void lowSufficiencyWarnsWithoutTouchingOrders() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("1.2", "-50")));

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, atLeastOnce()).event(eq(BotEventLevel.WARN), eq(BotEventType.RISK_BLOCKED),
                contains("Обеспечение счёта на пределе"));
        verify(gateway, never()).cancelAll(any());
        verify(gateway, never()).cancel(any(), any());
    }

    @Test
    @DisplayName("маржин-колл сообщается как ошибка, но позиция всё равно не закрывается")
    void marginCallIsReportedAsError() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("0.8", "1500")));

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, atLeastOnce()).event(eq(BotEventLevel.ERROR), eq(BotEventType.RISK_BLOCKED),
                contains("Маржин-колл"));
        verify(gateway, never()).cancelAll(any());
        verify(ctx, never()).requestStop(any());
    }

    /** Тик частый, а событие громкое: повторять его каждую минуту незачем. */
    @Test
    @DisplayName("о нехватке сообщается один раз за эпизод")
    void warnsOncePerEpisode() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("1.2", "-50")));

        GridStrategy strategy = started();
        strategy.onTick();
        strategy.onTick();
        strategy.onTick();

        verify(ctx, times(1)).event(eq(BotEventLevel.WARN), eq(BotEventType.RISK_BLOCKED), any());
    }

    /** Отпустило — следующее ухудшение обязано быть сказано вслух заново. */
    @Test
    @DisplayName("после возврата в норму сторож снова готов предупредить")
    void rearmsAfterRecovery() {
        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("1.2", "-50")));
        GridStrategy strategy = started();
        strategy.onTick();

        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("19.0", "-9000")));
        strategy.onTick();

        when(ctx.marginAttributes()).thenReturn(Optional.of(margin("1.1", "-10")));
        strategy.onTick();

        verify(ctx, times(2)).event(eq(BotEventLevel.WARN), eq(BotEventType.RISK_BLOCKED), any());
    }

    /**
     * Неизвестное обеспечение — это «не знаем», а не «всё плохо».
     *
     * Площадка может не уметь отвечать про маржу вовсе, и тревожиться на каждом
     * таком тике значило бы утопить настоящий сигнал в шуме.
     */
    @Test
    @DisplayName("отсутствие показателей не вызывает тревоги")
    void unknownMarginIsSilent() {
        when(ctx.marginAttributes()).thenReturn(Optional.empty());

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, never()).event(any(BotEventLevel.class), eq(BotEventType.RISK_BLOCKED), any());
    }

    /** Сторож не имеет права уронить тик: он бережёт бота, а не наоборот. */
    @Test
    @DisplayName("ошибка чтения обеспечения не ломает тик")
    void failureToReadMarginDoesNotBreakTheTick() {
        when(ctx.marginAttributes()).thenThrow(new IllegalStateException("брокер молчит"));

        GridStrategy strategy = started();
        strategy.onTick();

        verify(ctx, never()).event(any(BotEventLevel.class), eq(BotEventType.RISK_BLOCKED), any());
    }

    private GridStrategy started() {
        GridStrategy strategy = new GridStrategy(autoConfig());
        strategy.onStart(ctx, reconciled());
        return strategy;
    }

    private MarginAttributes margin(String sufficiency, String missingFunds) {
        return new MarginAttributes(
                new BigDecimal("14000"), new BigDecimal("1300"), new BigDecimal("650"),
                new BigDecimal(sufficiency), new BigDecimal(missingFunds),
                new BigDecimal("2500"), "RUB", now);
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
