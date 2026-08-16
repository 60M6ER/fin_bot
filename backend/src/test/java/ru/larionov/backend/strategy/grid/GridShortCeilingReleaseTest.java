package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.GridRole;
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
import ru.larionov.backend.execution.RiskRejectedException;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Потолок короткой позиции: место под ним переезжает за ценой.
 *
 * Потолок считается по ПРОГНОЗУ — позиция плюс всё выставленное плюс новая заявка, —
 * поэтому его занимают и висящие заявки. У шортовой сетки при движении вниз верхние
 * продажи превращаются в застрявшую ёмкость: цена до них дойдёт нескоро, а место они
 * держат, и уровень у самой цены выставить уже нечем. Ровно это случилось на боевом
 * MAGN 14.08.2026, когда нога плеча заняла 85% потолка.
 *
 * Сам потолок при этом остаётся прежним: меняется только то, какие заявки его занимают.
 */
class GridShortCeilingReleaseTest {

    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");
    private final UUID botId = UUID.randomUUID();

    private StrategyContext ctx;
    private ExecutionGateway gateway;
    private final List<BotOrderView> open = new ArrayList<>();
    private final List<UUID> cancelled = new ArrayList<>();
    private final List<PlaceIntent> placed = new ArrayList<>();

    /** Потолок пускает ровно две открывающие заявки: третья обязана кого-то подвинуть. */
    private int ceiling = 2;
    private int maxActiveOrders = 10;

    @Test
    @DisplayName("ближняя заявка вытесняет дальнюю, когда потолок занят")
    void nearOrderPushesOutTheFarthestOne() {
        GridStrategy strategy = started();
        // Две продажи уже стоят: дальняя по 21.0 и ближняя по 20.6.
        BotOrderView far = sell("21.0", 10);
        BotOrderView near = sell("20.6", 6);
        open.add(far);
        open.add(near);

        // Цена внизу диапазона — сетка хочет занять уровень 20.2, ближайший к рынку.
        strategy.onPrice(price("20.15"));

        assertThat(cancelled)
                .as("первой уходит самая дальняя от цены, а не первая попавшаяся")
                .startsWith(far.id());
        assertThat(placed)
                .as("и на освободившееся место встала ближняя")
                .anySatisfy(intent -> assertThat(intent.limitPrice()).isEqualByComparingTo("20.2"));
    }

    @Test
    @DisplayName("дальняя заявка не вытесняет ближнюю — иначе это карусель")
    void farOrderDoesNotPushOutANearerOne() {
        GridStrategy strategy = started();
        // Ближние уровни уже заняты: сетке остаётся проситься дальше от цены.
        open.add(sell("20.2", 2));
        open.add(sell("20.3", 3));

        strategy.onPrice(price("20.15"));

        assertThat(cancelled)
                .as("менять ближнее на дальнее бот не вправе: на следующем проходе вернул бы обратно")
                .isEmpty();
    }

    @Test
    @DisplayName("заявку выхода из плеча ради места не снимают")
    void hedgeExitIsNeverSacrificed() {
        GridStrategy strategy = started();
        // Выход из плеча стоит дальше всех — и всё равно неприкосновенен.
        BotOrderView exit = hedgeExit("22.0");
        open.add(exit);
        open.add(sell("20.6", 6));

        strategy.onPrice(price("20.15"));

        assertThat(cancelled)
                .as("это защита непокрытой позиции, а не ёмкость под потолком")
                .doesNotContain(exit.id());
    }

    @Test
    @DisplayName("повторный риск-отказ того же уровня не шумит на каждом тике")
    void repeatedRiskRejectionOnTheSameLevelIsReportedOnce() {
        ceiling = 0;
        GridStrategy strategy = started();
        clearInvocations(ctx);

        strategy.onPrice(price("20.15"));
        strategy.onPrice(price("20.15"));
        strategy.onPrice(price("20.15"));

        verify(ctx, times(1)).event(eq(BotEventType.RISK_BLOCKED), contains("денежный потолок"));
    }

    @Test
    @DisplayName("успешная постановка очищает молчание для уровня")
    void successfulPlacementClearsRiskRejectionSilence() {
        ceiling = 0;
        maxActiveOrders = 1;
        GridStrategy strategy = started();
        clearInvocations(ctx);

        strategy.onPrice(price("20.15"));
        strategy.onPrice(price("20.15"));

        ceiling = 1;
        strategy.onPrice(price("20.15"));
        open.clear();

        ceiling = 0;
        strategy.onPrice(price("20.15"));

        verify(ctx, times(2)).event(eq(BotEventType.RISK_BLOCKED), contains("денежный потолок"));
    }

    // ==============================
    // ИНФРАСТРУКТУРА
    // ==============================

    private GridStrategy started() {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);

        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        when(ctx.clock()).thenReturn(clock);
        when(ctx.botId()).thenReturn(botId);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(
                TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                true, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                true, true, new BigDecimal("1000"), new BigDecimal("100000")));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.carryDailyRate()).thenReturn(BigDecimal.ZERO);
        when(ctx.marginAttributes()).thenReturn(Optional.empty());
        when(ctx.inventory()).thenReturn(ru.larionov.backend.accounting.Inventory.empty());
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.empty());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) ->
                new FeeInfo(new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId)).thenReturn(price("20.15"));

        when(gateway.openOrders(botId)).thenAnswer(i -> List.copyOf(open));
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.reconcile(any())).thenReturn(reconciled());

        // Потолок: пока открывающих заявок меньше предела — пускаем, иначе отказ
        // ровно с той причиной, которую стратегия умеет разбирать.
        when(gateway.placeLimit(any(), any())).thenAnswer(i -> {
            PlaceIntent intent = i.getArgument(1);
            if (intent.role() == GridRole.OPEN && open.size() >= ceiling) {
                throw new RiskRejectedException("Короткая позиция вышла бы за денежный потолок",
                        RiskRejectedException.Reason.SHORT_NOTIONAL_CEILING);
            }
            placed.add(intent);
            open.add(sell(intent.limitPrice().toPlainString(), intent.gridLevel()));
            return open.get(open.size() - 1);
        });
        org.mockito.Mockito.doAnswer(i -> {
            UUID id = i.getArgument(1);
            cancelled.add(id);
            open.removeIf(o -> o.id().equals(id));
            return null;
        }).when(gateway).cancel(any(), any());

        GridStrategy strategy = new GridStrategy(shortConfig());
        strategy.onStart(ctx, reconciled());
        strategy.onReconcile(reconciled());
        placed.clear();
        cancelled.clear();
        return strategy;
    }

    private BotOrderView sell(String price, Integer level) {
        return order(OrderSide.SELL, price, level, OrderPurpose.GRID, GridRole.OPEN);
    }

    private BotOrderView hedgeExit(String price) {
        return order(OrderSide.BUY, price, null, OrderPurpose.RECOVERY, GridRole.CLOSE);
    }

    private BotOrderView order(OrderSide side, String price, Integer level,
                               OrderPurpose purpose, GridRole role) {
        UUID id = UUID.randomUUID();
        return new BotOrderView(id, id.toString(), "exch-" + id, side, OrderStatus.NEW, level,
                purpose, role, BigDecimal.TEN, BigDecimal.ZERO, new BigDecimal(price),
                null, null, false, null, null, "rub", BigDecimal.ONE, true, null, now, now);
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, BigDecimal.ZERO);
    }

    private LastPrice price(String value) {
        return new LastPrice(instrumentId, new Price(new BigDecimal(value), "rub"), now);
    }

    /** Шортовая сетка 20.0..21.0: продажи открывают позицию, покупки её закрывают. */
    private GridConfig shortConfig() {
        return new GridConfig(
                new BigDecimal("20.0"), new BigDecimal("21.0"), 10, new BigDecimal("10"), maxActiveOrders,
                GridConfig.RangeExitAction.STOP_BUYING, null, null, true, false,
                null, 6, new BigDecimal("2"), new BigDecimal("0.01"), new BigDecimal("0.15"),
                null, 300, new BigDecimal("0.002"), 1200, 0, null,
                null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                GridDirection.SHORT, true, 1);
    }
}
