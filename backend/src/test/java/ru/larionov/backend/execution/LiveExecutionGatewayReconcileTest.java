package ru.larionov.backend.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.order.CommissionSource;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.order.OrderFee;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;
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
    @Autowired
    private AccountingService accounting;
    @Autowired
    private MoneyLedgerRepository ledgerRepo;

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
                1, // лотность 1: отдельный тест ниже проверяет случай лотности 10
                null, null, null, null);

        exchange = new FakeOrdersApi();
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        gateway = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);
    }

    /**
     * Тест работает с той же базой, что и приложение, поэтому за собой убирает:
     * иначе журнал разработчика зарастал бы тестовыми ордерами и событиями.
     */
    @AfterEach
    void cleanUp() {
        orderRepo.deleteAll(journal());
        ledgerRepo.deleteAll(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false));
        events.deleteAllForBot(botId);
    }

    private PlaceIntent buyOne() {
        return new PlaceIntent(OrderSide.BUY, 1, new BigDecimal("100.00"), 3);
    }

    private PlaceIntent buyLots(long lots) {
        return new PlaceIntent(OrderSide.BUY, lots, new BigDecimal("100.00"), 3);
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
    void streamFillWithoutFeeGetsCentralFeeEstimateAndRestFactWinsLater() {
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        client.feeInfo = new ru.larionov.backend.exchange.api.model.FeeInfo(
                new BigDecimal("0.001"), new BigDecimal("0.002"));
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();

        var streamFill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-" + clientOrderId),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(clientOrderId),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.FILLED, null, null);

        gw.applyOrderEvent(ctx, streamFill);

        BotOrderEntity estimated = journal().get(0);
        assertThat(estimated.getFee()).isEqualByComparingTo("0.100000000");
        assertThat(estimated.isFeeActual()).isFalse();
        assertThat(estimated.getFeeSource()).isEqualTo(CommissionSource.BROKER_RATE_ESTIMATE.name());

        exchange.accepted.put(clientOrderId, new ru.larionov.backend.exchange.api.model.order.OrderState(
                streamFill.orderId(), streamFill.clientOrderId(), streamFill.accountId(), streamFill.instrumentId(),
                streamFill.side(), streamFill.requestedQuantity(), streamFill.executedQuantity(),
                streamFill.limitPrice(), streamFill.averageExecutedPrice(),
                OrderFee.actual(new BigDecimal("0.07"), "rub", CommissionSource.EXCHANGE_EXECUTED),
                OrderStatus.FILLED, streamFill.createdAt(), streamFill.updatedAt()));

        gw.reconcile(ctx);

        BotOrderEntity actual = journal().get(0);
        assertThat(actual.getFee()).isEqualByComparingTo("0.07");
        assertThat(actual.isFeeActual()).isTrue();
        assertThat(actual.getFeeSource()).isEqualTo(CommissionSource.EXCHANGE_EXECUTED.name());
        assertThat(actual.getFeeCurrency()).isEqualTo("rub");
    }

    @Test
    void ledgerIsIdempotentForRepeatedSameFill() {
        gateway.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();
        exchange.fill(clientOrderId, OrderFee.actual(new BigDecimal("0.10"), "rub", CommissionSource.EXCHANGE_EXECUTED));

        gateway.reconcile(ctx);
        gateway.reconcile(ctx);
        gateway.reconcile(ctx);

        assertThat(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false))
                .hasSize(1)
                .first()
                .satisfies(row -> {
                    assertThat(row.getEntryType().name()).isEqualTo("TRADE_BUY");
                    assertThat(row.getAmount()).isEqualByComparingTo("-100.10");
                    assertThat(row.isCommissionEstimated()).isFalse();
                });
    }

    @Test
    void ledgerRecordsOnlyDeltaForPartialFills() {
        gateway.placeLimit(ctx, buyLots(3));
        String clientOrderId = journal().get(0).getClientOrderId();

        exchange.partialFill(clientOrderId, 1,
                OrderFee.actual(new BigDecimal("0.10"), "rub", CommissionSource.EXCHANGE_EXECUTED));
        gateway.reconcile(ctx);

        exchange.fill(clientOrderId,
                OrderFee.actual(new BigDecimal("0.30"), "rub", CommissionSource.EXCHANGE_EXECUTED));
        gateway.reconcile(ctx);
        gateway.reconcile(ctx);

        var rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.getEntryType()).isEqualTo(LedgerEntryType.TRADE_BUY));
        assertThat(rows).extracting("executedLotsCum").containsExactly(1L, 3L);
        assertThat(rows).extracting("lots").containsExactly(1L, 2L);
        assertThat(rows).extracting("commission")
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("0.10"), new BigDecimal("0.20"));
        assertThat(accounting.summary(botId, false).cashFlow()).isEqualByComparingTo("-300.30");
    }

    @Test
    void actualCommissionCreatesCorrectionWhenItDiffersFromEstimate() {
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        client.feeInfo = new ru.larionov.backend.exchange.api.model.FeeInfo(
                new BigDecimal("0.001"), new BigDecimal("0.002"));
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();

        var estimatedFill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-" + clientOrderId),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(clientOrderId),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.FILLED, null, null);
        gw.applyOrderEvent(ctx, estimatedFill);

        exchange.accepted.put(clientOrderId, new ru.larionov.backend.exchange.api.model.order.OrderState(
                estimatedFill.orderId(), estimatedFill.clientOrderId(), estimatedFill.accountId(),
                estimatedFill.instrumentId(), estimatedFill.side(), estimatedFill.requestedQuantity(),
                estimatedFill.executedQuantity(), estimatedFill.limitPrice(), estimatedFill.averageExecutedPrice(),
                OrderFee.actual(new BigDecimal("0.07"), "rub", CommissionSource.EXCHANGE_EXECUTED),
                OrderStatus.FILLED, estimatedFill.createdAt(), estimatedFill.updatedAt()));

        gw.reconcile(ctx);
        gw.reconcile(ctx);

        var rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getEntryType()).isEqualTo(LedgerEntryType.TRADE_BUY);
        assertThat(rows.get(0).getCommission()).isEqualByComparingTo("0.100000000");
        assertThat(rows.get(0).isCommissionEstimated()).isTrue();
        assertThat(rows.get(1).getEntryType()).isEqualTo(LedgerEntryType.COMMISSION_CORRECTION);
        assertThat(rows.get(1).getCommission()).isEqualByComparingTo("-0.030000000");
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("0.030000000");
        assertThat(rows.get(1).isCommissionEstimated()).isFalse();
        assertThat(accounting.summary(botId, false).cashFlow()).isEqualByComparingTo("-100.07");
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
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        ReconcileResult result = gw.reconcile(ctx);

        assertThat(result.positionMismatch()).isEqualByComparingTo("4");
        assertThat(result.hasFindings())
                .as("Расхождение позиции — повод для внимания, а не для молчаливой торговли")
                .isTrue();
    }

    /**
     * Регрессия на баг, стоивший реальных денег.
     *
     * Биржа отдаёт позицию в ШТУКАХ, журнал считает в ЛОТАХ. При лотности 10 покупка
     * одного лота выглядела как позиция «10», стратегия считала девять лотов
     * непроданными и заполняла продажами все свободные уровни — то есть продавала
     * втрое больше, чем купила.
     */
    @Test
    void exchangePositionInSharesIsConvertedToLots() {
        BotExecutionContext lot10 = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false,
                10, // как у MAGN: 10 бумаг в лоте
                null, null, null, null);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // Купили 1 лот — биржа отвечает «10 штук».
        client.exchangePosition = new BigDecimal("10");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(lot10, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();
        exchange.fill(clientOrderId);

        ReconcileResult result = gw.reconcile(lot10);

        assertThat(result.positionLots())
                .as("Позиция обязана быть в лотах: 10 штук при лотности 10 — это 1 лот")
                .isEqualByComparingTo("1");
        assertThat(result.positionMismatch())
                .as("Журнал тоже знает про 1 лот, значит расхождения нет")
                .isEqualByComparingTo("0");
    }

    @Test
    void mismatchIsStillReportedWhenExchangeGenuinelyDisagrees() {
        BotExecutionContext lot10 = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false, 10, null, null, null, null);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // 30 штук = 3 лота, а куплен будет один: расхождение настоящее.
        client.exchangePosition = new BigDecimal("30");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(lot10, buyOne());
        exchange.fill(journal().get(0).getClientOrderId());

        ReconcileResult result = gw.reconcile(lot10);

        assertThat(result.positionLots()).isEqualByComparingTo("3");
        assertThat(result.positionMismatch()).isEqualByComparingTo("2");
        assertThat(result.hasFindings()).isTrue();
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
