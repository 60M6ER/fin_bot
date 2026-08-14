package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataApi;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.Price;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.execution.ExecutionGateway;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Зеркальность направлений.
 *
 * Один и тот же ценовой сценарий прогоняется дважды: лонговой сеткой на ценах p и
 * шортовой на отражённых ценах {@code 2·mid − p}. Заявки обязаны получиться
 * зеркальными — сторона инвертирована, уровень отражён, цена отражена.
 *
 * Смысл теста в том, чтобы сделать абстракцию направления ДОВЕРЕННОЙ. Проверять
 * шортовую сетку по отдельности значило бы переписать все её ожидания руками и
 * заново ошибиться в тех же местах; сравнение с лонгом ловит именно расхождение
 * зеркал, а его руками не подделать.
 */
class GridDirectionMirrorTest {

    private final InstrumentId instrumentId = new InstrumentId("uid-1", null);
    private final Instant now = Instant.parse("2026-01-08T12:00:00Z");

    /** Центр отражения: цены лонга и шорта симметричны относительно него. */
    private static final BigDecimal MID = new BigDecimal("100");

    private UUID botId;
    private StrategyContext ctx;
    private ExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        botId = UUID.randomUUID();
    }

    /**
     * Открывающие заявки ставятся зеркально.
     *
     * Лонг занимает уровни НИЖЕ цены и покупает, шорт — уровни ВЫШЕ и продаёт.
     * Уровень k лонга обязан соответствовать уровню N−k шорта.
     */
    @Test
    @DisplayName("открывающие заявки лонга и шорта зеркальны по стороне, уровню и цене")
    void openingOrdersAreMirrored() {
        List<PlaceIntent> longIntents = run(GridDirection.LONG, new BigDecimal("100"));
        List<PlaceIntent> shortIntents = run(GridDirection.SHORT, new BigDecimal("100"));

        assertThat(longIntents).isNotEmpty();
        assertThat(shortIntents)
                .as("шорт обязан выставить столько же заявок, сколько лонг")
                .hasSameSizeAs(longIntents);

        for (int i = 0; i < longIntents.size(); i++) {
            PlaceIntent longOne = longIntents.get(i);
            PlaceIntent shortOne = shortIntents.get(i);

            assertThat(longOne.side()).isEqualTo(OrderSide.BUY);
            assertThat(shortOne.side())
                    .as("зеркало стороны: лонг набирает покупкой, шорт — продажей")
                    .isEqualTo(OrderSide.SELL);

            assertThat(longOne.role()).isEqualTo(GridRole.OPEN);
            assertThat(shortOne.role())
                    .as("обе заявки ОТКРЫВАЮТ позицию, несмотря на разные стороны")
                    .isEqualTo(GridRole.OPEN);

            assertThat(shortOne.gridLevel())
                    .as("уровень k лонга отражается в N−k шорта")
                    .isEqualTo(LEVELS - longOne.gridLevel());

            assertThat(mirror(longOne.limitPrice()))
                    .as("цена отражена относительно центра")
                    .isEqualByComparingTo(shortOne.limitPrice());
        }
    }

    /** Ближайший к рынку уровень занимается первым в обе стороны. */
    @Test
    @DisplayName("обе сетки начинают с ближайшего к рынку уровня")
    void bothStartFromTheLevelNearestToTheMarket() {
        List<PlaceIntent> longIntents = run(GridDirection.LONG, new BigDecimal("100"));
        List<PlaceIntent> shortIntents = run(GridDirection.SHORT, new BigDecimal("100"));

        assertThat(longIntents.get(0).limitPrice())
                .as("лонг начинает с ближайшего уровня ПОД ценой")
                .isLessThan(MID);
        assertThat(shortIntents.get(0).limitPrice())
                .as("шорт начинает с ближайшего уровня НАД ценой")
                .isGreaterThan(MID);
    }

    // ==============================
    // ИНФРАСТРУКТУРА
    // ==============================

    private static final int LEVELS = 10;
    private static final BigDecimal LOWER = new BigDecimal("95");
    private static final BigDecimal UPPER = new BigDecimal("105");

    /** Отражение цены относительно центра: 2·mid − p. */
    private BigDecimal mirror(BigDecimal price) {
        return MID.multiply(BigDecimal.valueOf(2)).subtract(price);
    }

    private List<PlaceIntent> run(GridDirection direction, BigDecimal price) {
        prepare(direction);
        GridStrategy strategy = new GridStrategy(config(direction));
        strategy.onStart(ctx, reconciled());
        strategy.onPrice(new LastPrice(instrumentId, new Price(price, "rub"), now));

        ArgumentCaptor<PlaceIntent> captor = ArgumentCaptor.forClass(PlaceIntent.class);
        verify(gateway, atLeast(1)).placeLimit(any(), captor.capture());
        return captor.getAllValues();
    }

    private void prepare(GridDirection direction) {
        ctx = mock(StrategyContext.class);
        ExchangeClient exchange = mock(ExchangeClient.class);
        MarketDataApi marketData = mock(MarketDataApi.class);
        gateway = mock(ExecutionGateway.class);

        boolean shortMode = direction == GridDirection.SHORT;

        when(ctx.botId()).thenReturn(botId);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        when(ctx.clock()).thenReturn(clock);
        when(ctx.gateway()).thenReturn(gateway);
        when(ctx.constraints()).thenReturn(
                TradingConstraints.wholeLots(1, new BigDecimal("0.01"), "rub"));
        when(ctx.execution()).thenReturn(new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc"), instrumentId,
                true, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null, "rub",
                shortMode, shortMode, new BigDecimal("1000"), new BigDecimal("1000000")));
        when(ctx.exchange()).thenReturn(exchange);
        when(ctx.realizedPnl()).thenReturn(BigDecimal.ZERO);
        when(ctx.carryDailyRate()).thenReturn(BigDecimal.ZERO);
        when(ctx.marginAttributes()).thenReturn(Optional.empty());
        when(ctx.loadState(GridStrategyState.class)).thenReturn(Optional.empty());

        when(exchange.marketData()).thenReturn(marketData);
        when(exchange.fees()).thenReturn((accountId, id) -> new FeeInfo(
                new BigDecimal("0.0005"), new BigDecimal("0.0005")));
        when(marketData.getTradingStatus(instrumentId)).thenReturn(
                new TradingStatusEvent(instrumentId, true, true, "NORMAL_TRADING", now));
        when(marketData.getLastPrice(instrumentId))
                .thenReturn(new LastPrice(instrumentId, new Price(MID, "rub"), now));
        when(gateway.openOrders(botId)).thenReturn(List.of());
        when(gateway.levelOrders(eq(botId), any())).thenReturn(List.of());
        when(gateway.reconcile(any())).thenReturn(reconciled());
    }

    /**
     * Диапазон один и тот же для обеих сеток — он симметричен относительно центра,
     * поэтому отражать его не нужно, и зеркало проверяется в чистом виде.
     */
    private GridConfig config(GridDirection direction) {
        boolean shortMode = direction == GridDirection.SHORT;
        return new GridConfig(
                LOWER, UPPER, LEVELS, new BigDecimal("1"), LEVELS,
                null, null, null, true, false,
                null, null, null, null, null,
                null, null, null, null, 0, null,
                null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                direction, shortMode, null);
    }

    private ReconcileResult reconciled() {
        return new ReconcileResult(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, BigDecimal.ZERO);
    }
}
