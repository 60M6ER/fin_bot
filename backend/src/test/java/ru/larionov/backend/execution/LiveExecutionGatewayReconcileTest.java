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
import java.time.Duration;
import java.time.Instant;
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
                // Заявочная единица 1: отдельный тест ниже проверяет лотность 10.
                BigDecimal.ONE, BigDecimal.ONE, null,
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
        return new PlaceIntent(OrderSide.BUY, new BigDecimal("1"), new BigDecimal("100.00"), 3);
    }

    private PlaceIntent buyQuantity(String quantity) {
        return new PlaceIntent(OrderSide.BUY, new BigDecimal(quantity), new BigDecimal("100.00"), 3);
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
        assertThat(row.getExecutedQuantity()).isEqualByComparingTo("1");
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

    /**
     * Комиссия, удержанная ИЗ полученной монеты, делает окончательное количество
     * МЕНЬШЕ исполненного объёма. Журнал обязан принять меньшее число.
     *
     * Раньше здесь стоял max(), и это стоило простоя на Poloniex 07.08.2026: стрим
     * (а затем и сверка) приносил брутто, оно побеждало по max() навсегда, позиция
     * журнала оставалась больше фактической ровно на комиссию, встречную продажу
     * биржа отбивала как необеспеченную, а сверка вставала намертво — исправить
     * её не могло уже никакое последующее уточнение.
     */
    @Test
    void confirmedFeeLowersTheRecordedQuantityInsteadOfLosingToTheGrossFigure() {
        gateway.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();

        // Стрим знает только брутто: сколько монет биржа списала со встречной стороны.
        var grossFill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-" + clientOrderId),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(clientOrderId),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.FILLED, null, null);
        gateway.applyOrderEvent(ctx, grossFill);

        assertThat(journal().get(0).getExecutedQuantity())
                .as("до подтверждения комиссии брутто — единственное, что известно")
                .isEqualByComparingTo("1");

        // Сверка достаёт сделки заявки: комиссия удержана монетой, зачислено 0.998.
        // Последний аргумент и есть то самое заявление биржи «количество уже нетто».
        exchange.accepted.put(clientOrderId, new ru.larionov.backend.exchange.api.model.order.OrderState(
                grossFill.orderId(), grossFill.clientOrderId(), grossFill.accountId(), grossFill.instrumentId(),
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("0.998"),
                new BigDecimal("100"), new BigDecimal("100"),
                OrderFee.actual(new BigDecimal("0.20"), "rub", CommissionSource.EXCHANGE_EXECUTED),
                OrderStatus.FILLED, null, null, true));

        gateway.reconcile(ctx);

        assertThat(journal().get(0).getExecutedQuantity())
                .as("подтверждённый биржей расчёт — это и есть позиция, которой бот владеет")
                .isEqualByComparingTo("0.998");
    }

    /**
     * Обратная защита осталась на месте: НЕподтверждённое чтение занизить количество
     * не может. Частичный или запоздавший ответ не должен стирать уже известный объём.
     */
    @Test
    void unconfirmedReadStillCannotLowerTheKnownQuantity() {
        gateway.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();

        var fill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-" + clientOrderId),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(clientOrderId),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.FILLED, null, null);
        gateway.applyOrderEvent(ctx, fill);

        var stale = new ru.larionov.backend.exchange.api.model.order.OrderState(
                fill.orderId(), fill.clientOrderId(), fill.accountId(), fill.instrumentId(),
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("0.4"),
                new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.PARTIALLY_FILLED, null, null);
        gateway.applyOrderEvent(ctx, stale);

        assertThat(journal().get(0).getExecutedQuantity()).isEqualByComparingTo("1");
    }

    /**
     * Форма T-Invest: комиссия ПОДТВЕРЖДЕНА, но удержана деньгами и количества не трогает.
     *
     * Такое состояние брокер отдаёт уже при частичном исполнении, поэтому «комиссия
     * подтверждена» само по себе НЕ может быть основанием занизить количество. Признаком
     * служит только прямое заявление биржи о том, что она удержала комиссию монетой, —
     * иначе защита от запоздалого чтения молча исчезла бы для T-Invest заодно с Poloniex.
     */
    @Test
    void confirmedButNotNettedFeeLeavesTheMonotonicGuardInPlace() {
        gateway.placeLimit(ctx, buyOne());
        String clientOrderId = journal().get(0).getClientOrderId();

        var fill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("exch-" + clientOrderId),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(clientOrderId),
                ctx.accountId(), ctx.instrumentId(), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                null, OrderStatus.FILLED, null, null);
        gateway.applyOrderEvent(ctx, fill);

        var staleWithRealFee = new ru.larionov.backend.exchange.api.model.order.OrderState(
                fill.orderId(), fill.clientOrderId(), fill.accountId(), fill.instrumentId(),
                OrderSide.BUY, BigDecimal.ONE, new BigDecimal("0.4"),
                new BigDecimal("100"), new BigDecimal("100"),
                OrderFee.actual(new BigDecimal("0.05"), "rub", CommissionSource.EXCHANGE_EXECUTED),
                OrderStatus.PARTIALLY_FILLED, null, null);
        gateway.applyOrderEvent(ctx, staleWithRealFee);

        assertThat(journal().get(0).getExecutedQuantity())
                .as("рублёвая комиссия лотов не отнимает — количество занижать нечему")
                .isEqualByComparingTo("1");
    }

    @Test
    void streamEventForAnotherBotIsIgnored() {
        UUID otherBotId = UUID.randomUUID();
        BotOrderEntity foreignOrder = orderRepo.save(BotOrderEntity.builder()
                .botId(otherBotId)
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid("uid-2")
                .clientOrderId(UUID.randomUUID().toString())
                .side(OrderSide.BUY)
                .status(OrderStatus.NEW)
                .gridLevel(4)
                .requestedQuantity(BigDecimal.ONE)
                .executedQuantity(BigDecimal.ZERO)
                .limitPrice(new BigDecimal("22.27"))
                .exchangeLotSize(BigDecimal.TEN)
                .dryRun(false)
                .build());

        var foreignFill = new ru.larionov.backend.exchange.api.model.order.OrderState(
                new ru.larionov.backend.exchange.api.model.id.OrderId("foreign-exchange-id"),
                new ru.larionov.backend.exchange.api.model.id.ClientOrderId(foreignOrder.getClientOrderId()),
                ctx.accountId(), new InstrumentId("uid-2", null), OrderSide.BUY,
                BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("22.27"), new BigDecimal("22.27"),
                null, OrderStatus.FILLED, null, null);

        try {
            assertThat(gateway.applyOrderEvent(ctx, foreignFill)).isEmpty();

            BotOrderEntity unchanged = orderRepo.findById(foreignOrder.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(OrderStatus.NEW);
            assertThat(unchanged.getExecutedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(otherBotId, false)).isEmpty();
        } finally {
            ledgerRepo.deleteAll(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(otherBotId, false));
            orderRepo.deleteById(foreignOrder.getId());
            events.deleteAllForBot(otherBotId);
        }
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
        gateway.placeLimit(ctx, buyQuantity("3"));
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
        assertThat(rows).extracting("executedQuantityCum")
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("1"), new BigDecimal("3"));
        assertThat(rows).extracting("quantity")
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("1"), new BigDecimal("2"));
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
        var summary = accounting.summary(botId, false);
        assertThat(summary.cashFlow()).isEqualByComparingTo("-100.07");
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("100.07");
        assertThat(summary.realizedPnl()).isEqualByComparingTo(BigDecimal.ZERO);
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
     * Раньше журнал считал в ЛОТАХ, а биржа отвечала ШТУКАМИ: при лотности 10 покупка
     * одного лота выглядела как позиция «10», стратегия считала девять лотов
     * непроданными и заполняла продажами все свободные уровни, то есть продавала
     * втрое больше, чем купила.
     *
     * Теперь домен считает в штуках, а лотность живёт только на границе с биржей.
     * Тест стережёт именно эту границу: 10 штук обязаны уйти на биржу как ОДНА
     * заявочная единица и вернуться исполнением на те же 10 штук.
     */
    @Test
    void quantityRoundTripsThroughExchangeLotUnits() {
        BotExecutionContext lot10 = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false,
                BigDecimal.TEN, BigDecimal.TEN, null, // как у MAGN: 10 бумаг в лоте
                null, null, null, null);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // Купили 10 штук — биржа держит их как 1 лот и отвечает «10 штук» по позиции.
        client.exchangePosition = new BigDecimal("10");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(lot10, buyQuantity("10"));

        assertThat(exchange.accepted.values().iterator().next().requestedQuantity())
                .as("на биржу уходит одна заявочная единица, а не десять")
                .isEqualByComparingTo("1");

        String clientOrderId = journal().get(0).getClientOrderId();
        exchange.fill(clientOrderId);

        ReconcileResult result = gw.reconcile(lot10);

        assertThat(journal().get(0).getExecutedQuantity())
                .as("исполнение вернулось в штуках")
                .isEqualByComparingTo("10");
        assertThat(result.position())
                .as("позиция биржи и журнала выражены одинаково — в штуках")
                .isEqualByComparingTo("10");
        assertThat(result.positionMismatch())
                .as("расхождения нет")
                .isEqualByComparingTo("0");
    }

    /**
     * Тот самый вопрос, который задают перед первым запуском после миграции:
     * в форме теперь написано «20», а раньше стояло «2 лота» — не улетит ли на биржу
     * заявка на 20 ЛОТОВ, то есть 200 бумаг вместо двадцати.
     *
     * Числа взяты как есть: MAGN, лот 10, конфигурация до миграции lotsPerOrder=2,
     * после — quantityPerOrder=20. На биржу обязаны уйти 2 заявочные единицы.
     */
    @Test
    void migratedTwoLotsOfMagnStillReachTheExchangeAsTwoLots() {
        BotExecutionContext magn = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false,
                BigDecimal.TEN, BigDecimal.TEN, null,
                null, null, null, null);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        client.exchangePosition = new BigDecimal("20");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        // Ровно то, что после миграции лежит в strategy_config: 2 лота × 10 бумаг.
        gw.placeLimit(magn, buyQuantity("20"));

        assertThat(exchange.accepted.values().iterator().next().requestedQuantity())
                .as("на биржу уходит 2 лота — не 20 и не 200 бумаг")
                .isEqualByComparingTo("2");

        assertThat(journal().get(0).getRequestedQuantity())
                .as("в журнале при этом штуки, как и во всём домене")
                .isEqualByComparingTo("20");

        exchange.fill(journal().get(0).getClientOrderId());
        ReconcileResult result = gw.reconcile(magn);

        assertThat(journal().get(0).getExecutedQuantity()).isEqualByComparingTo("20");
        assertThat(result.positionMismatch())
                .as("позиция биржи (20 бумаг) сходится с журналом")
                .isEqualByComparingTo("0");
    }

    /**
     * Некратное лоту количество не превращается в дробную заявку.
     *
     * 25 бумаг при лоте 10 — это 2.5 лота, чего биржа не примет. Округление вниз
     * обязано случиться ДО пересчёта, иначе на провод уйдёт дробь, а вернётся отказ
     * либо, что хуже, молча усечённая заявка не того размера.
     */
    @Test
    void quantityNotDivisibleByLotIsRoundedDownBeforeItReachesTheExchange() {
        BotExecutionContext magn = new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false,
                BigDecimal.TEN, BigDecimal.TEN, null,
                null, null, null, null);

        FakeExchangeClient client = new FakeExchangeClient(exchange);
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(magn, buyQuantity("25"));

        assertThat(exchange.accepted.values().iterator().next().requestedQuantity())
                .as("целые 2 лота, а не 2.5")
                .isEqualByComparingTo("2");
        assertThat(journal().get(0).getRequestedQuantity())
                .as("журнал хранит то, что реально заказано, — 20 бумаг, а не запрошенные 25")
                .isEqualByComparingTo("20");
    }

    private BotExecutionContext lot10() {
        return new BotExecutionContext(
                botId, UUID.randomUUID(),
                new AccountId("acc-1"),
                new InstrumentId("uid-1", null),
                false, BigDecimal.TEN, BigDecimal.TEN, null, null, null, null, null);
    }

    /**
     * На счёте больше, чем числится за ботом, — это НЕ его беда.
     *
     * Счёт общий: там лежат монеты соседнего бота на том же инструменте, ручные
     * покупки владельца и неторгуемая пыль от прошлых циклов. Пока «позицией бота»
     * считался весь остаток счёта, из излишка следовало три неверных вывода подряд:
     * новый бот с пустым журналом считал чужое своим и не стартовал, сверка вечно
     * сообщала о расхождении, а принудительная ликвидация выставляла на продажу
     * ВЕСЬ остаток — то есть чужие монеты.
     */
    @Test
    void surplusOnTheSharedAccountIsNotClaimedByTheBot() {
        BotExecutionContext lot10 = lot10();
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // На счёте 30 штук, а бот купил 10. Остальные 20 — чужие.
        client.exchangePosition = new BigDecimal("30");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(lot10, buyQuantity("10"));
        exchange.fill(journal().get(0).getClientOrderId());

        ReconcileResult result = gw.reconcile(lot10);

        assertThat(result.position())
                .as("бот вправе распоряжаться только тем, что купил сам")
                .isEqualByComparingTo("10");
        assertThat(result.exchangePosition())
                .as("а весь остаток счёта остаётся доступен отдельной величиной")
                .isEqualByComparingTo("30");
        assertThat(result.positionMismatch())
                .as("расхождение видно как излишек — но это чужие 20 штук")
                .isEqualByComparingTo("20");
        assertThat(result.positionShortfall())
                .as("излишек на общем счёте не повод останавливать торговлю")
                .isFalse();
    }

    /**
     * Обратная сторона и единственная по-настоящему опасная: на счёте МЕНЬШЕ, чем
     * числится за ботом. Тогда встречные продажи окажутся необеспеченными — ровно
     * то, что случилось на Poloniex, когда комиссия была удержана монетой.
     */
    @Test
    void shortfallAgainstTheJournalIsReported() {
        BotExecutionContext lot10 = lot10();
        FakeExchangeClient client = new FakeExchangeClient(exchange);
        // Бот купит 10, а на счёте всего 4: шести штук не хватает.
        client.exchangePosition = new BigDecimal("4");
        LiveExecutionGateway gw = new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, () -> client);

        gw.placeLimit(lot10, buyQuantity("10"));
        exchange.fill(journal().get(0).getClientOrderId());

        ReconcileResult result = gw.reconcile(lot10);

        assertThat(result.position()).isEqualByComparingTo("10");
        assertThat(result.positionMismatch()).isEqualByComparingTo("-6");
        assertThat(result.positionShortfall()).isTrue();
        assertThat(result.hasFindings()).isTrue();
    }

    /**
     * Регрессия на отказ, остановивший торговлю на весь день.
     *
     * Запрос состояния падал (у нас — разомкнутый circuit breaker, в бою — необёрнутый
     * NOT_FOUND), запись навсегда оставалась PENDING, и дальше по цепочке: она держала
     * капитал в лимитах, числилась открытой заявкой и не давала ликвидации снять всё
     * перед закрытием позиции. Девять таких записей пережили все перезапуски.
     */
    @Test
    void unconfirmedPendingIsAbandonedOnceItIsOldEnough() {
        exchange.rejectOutright = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);
        exchange.rejectOutright = false;
        exchange.stateLookupFails = true;

        // Пока запись свежая — не трогаем: биржа ещё может ответить, и объявить
        // непринятым реально стоящий ордер было бы хуже, чем подождать.
        gateway.reconcile(ctx);
        assertThat(journal().get(0).getStatus()).isEqualTo(OrderStatus.PENDING);

        backdate(journal().get(0), Duration.ofMinutes(5));
        gateway.reconcile(ctx);

        BotOrderEntity row = journal().get(0);
        assertThat(row.getStatus())
                .as("Списка живых заявок биржи мы дождались, нашей записи в нём нет — она не принята")
                .isEqualTo(OrderStatus.REJECTED);
        assertThat(gateway.openOrders(botId))
                .as("Иначе ликвидация будет вечно ждать, пока список заявок опустеет")
                .isEmpty();
    }

    /** Подтверждённый биржей ордер не должен пострадать от того же правила. */
    @Test
    void acceptedOrderIsNeverAbandonedByAge() {
        gateway.placeLimit(ctx, buyOne());
        backdate(journal().get(0), Duration.ofHours(3));
        exchange.stateLookupFails = true;

        gateway.reconcile(ctx);

        assertThat(journal().get(0).getStatus()).isEqualTo(OrderStatus.NEW);
        assertThat(gateway.openOrders(botId)).hasSize(1);
    }

    /** cancelAll перед ликвидацией не должен спотыкаться о неразрешимую запись. */
    @Test
    void cancelAllClearsStalePendingSoLiquidationCanProceed() {
        exchange.rejectOutright = true;
        assertThatThrownBy(() -> gateway.placeLimit(ctx, buyOne())).isInstanceOf(RuntimeException.class);
        exchange.rejectOutright = false;
        exchange.stateLookupFails = true;
        backdate(journal().get(0), Duration.ofMinutes(5));

        gateway.cancelAll(ctx);

        assertThat(journal().get(0).getStatus().isTerminal()).isTrue();
        assertThat(gateway.openOrders(botId)).isEmpty();
    }

    private void backdate(BotOrderEntity order, Duration age) {
        order.setCreatedAt(Instant.now().minus(age));
        orderRepo.save(order);
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
