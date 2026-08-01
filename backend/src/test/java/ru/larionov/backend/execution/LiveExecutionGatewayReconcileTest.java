package ru.larionov.backend.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.BotEventService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Главная проверка этапа: оборванная постановка не приводит ни к потере ордера,
 * ни к дублю. Именно этот сценарий стоит реальных денег.
 *
 * Тест интеграционный намеренно: проверяется в том числе реальное отображение
 * журнала в БД, включая уникальность client_order_id.
 */
@SpringBootTest
class LiveExecutionGatewayReconcileTest {

    @Autowired
    private BotOrderRepository orderRepo;
    @Autowired
    private RiskGuard riskGuard;
    @Autowired
    private BotEventService events;

    private UUID botId;
    private BotExecutionContext ctx;
    private FakeOrdersApi exchange;
    private LiveExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        botId = UUID.randomUUID();
        ctx = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false,
                null, null, null, null);

        exchange = new FakeOrdersApi();
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        gateway = new LiveExecutionGateway(orderRepo, riskGuard, events, () -> client);
    }

    /**
     * Тест работает с той же базой, что и приложение, поэтому за собой убирает:
     * иначе журнал разработчика зарастал бы тестовыми ордерами и событиями.
     */
    @AfterEach
    void cleanUp() {
        orderRepo.deleteAll(journal());
        events.deleteAllForBot(botId);
    }

    private PlaceIntent buyOne() {
        return new PlaceIntent(OrderSide.BUY, 1, new BigDecimal("100.00"), 3);
    }

    private List<BotOrderEntity> journal() {
        return orderRepo.findAll().stream().filter(o -> o.getBotId().equals(botId)).toList();
    }

    @Test
    void journalRowIsWrittenBeforeTheNetworkCall() {
        exchange.rejectOutright = true;

        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne()))
                .hasMessageContaining("сеть недоступна");

        // Даже когда вызов не состоялся, намерение зафиксировано — иначе после рестарта
        // мы бы не знали, что вообще пытались что-то выставить.
        List<BotOrderEntity> rows = journal();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(rows.get(0).getClientOrderId()).isNotBlank();
    }

    @Test
    void timedOutPlacementIsResolvedByReconcileWithoutDuplicating() {
        // Биржа приняла ордер, но ответ до нас не дошёл.
        exchange.acceptThenTimeout = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne()))
                .hasMessageContaining("таймаут");

        assertThat(journal()).hasSize(1);
        String clientOrderId = journal().get(0).getClientOrderId();
        assertThat(journal().get(0).getStatus()).isEqualTo(OrderStatus.PENDING);

        // Сверка выясняет судьбу по НАШЕМУ идентификатору.
        ReconcileResult result = gateway.reconcile(ctx);

        List<BotOrderEntity> rows = journal();
        assertThat(rows)
                .as("Сверка не должна создавать вторую запись — это и был бы дубль ордера")
                .hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(rows.get(0).getExchangeOrderId()).isEqualTo("exch-" + clientOrderId);
        assertThat(result.openOrders()).hasSize(1);

        // И на бирже ровно одна попытка постановки.
        assertThat(exchange.placeAttempts).hasSize(1);
    }

    @Test
    void orderFilledDuringTheGapIsPickedUpByReconcile() {
        exchange.acceptThenTimeout = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);

        String clientOrderId = journal().get(0).getClientOrderId();
        // Пока мы «не знали» об ордере, он исполнился.
        exchange.fill(clientOrderId);

        gateway.reconcile(ctx);

        BotOrderEntity row = journal().get(0);
        assertThat(row.getStatus()).isEqualTo(OrderStatus.FILLED);
        assertThat(row.getExecutedLots()).isEqualTo(1);
        assertThat(journal()).hasSize(1);
    }

    @Test
    void orderNeverAcceptedIsMarkedRejectedSoItCanBePlacedAgain() {
        exchange.rejectOutright = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);

        gateway.reconcile(ctx);

        BotOrderEntity row = journal().get(0);
        assertThat(row.getStatus())
                .as("Биржа ордера не знает — значит его не приняли, и место в сетке свободно")
                .isEqualTo(OrderStatus.REJECTED);
        assertThat(row.getStatus().isTerminal()).isTrue();
        assertThat(gateway.openOrders(botId)).isEmpty();
    }

    @Test
    void repeatedReconcileIsIdempotent() {
        exchange.acceptThenTimeout = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);

        gateway.reconcile(ctx);
        gateway.reconcile(ctx);
        gateway.reconcile(ctx);

        assertThat(journal()).hasSize(1);
        assertThat(exchange.placeAttempts).hasSize(1);
    }

    @Test
    void positionMismatchWithExchangeIsDetected() {
        exchange.acceptThenTimeout = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);

        String clientOrderId = journal().get(0).getClientOrderId();
        exchange.fill(clientOrderId);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // Биржа считает, что у нас 5 лотов, а журнал будет знать про 1.
        client.exchangePosition = new BigDecimal("5");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, () -> client);

        ReconcileResult result = gw.reconcile(ctx);

        assertThat(result.positionMismatch()).isEqualByComparingTo("4");
        assertThat(result.hasFindings())
                .as("Расхождение позиции — повод для внимания, а не для молчаливой торговли")
                .isTrue();
    }

    @Test
    void streamEventForForeignOrderIsIgnored() {
        exchange.acceptThenTimeout = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);

        var foreign = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-foreign"),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(UUID.randomUUID().toString()),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("99"), new BigDecimal("99"),
                null, OrderStatus.FILLED, null, null);

        assertThat(gateway.applyOrderEvent(ctx, foreign))
                .as("Ручная сделка в приложении брокера — не ошибка, просто не наша")
                .isEmpty();
        assertThat(journal()).hasSize(1);
    }
}
