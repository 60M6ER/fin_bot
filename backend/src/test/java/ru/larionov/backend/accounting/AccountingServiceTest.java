package ru.larionov.backend.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.entity.InstrumentEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.BotEventRepository;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.repository.InstrumentRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AccountingServiceTest {

    private final UUID botId = UUID.randomUUID();
    private final BotExecutionContext ctx = new BotExecutionContext(
            botId, UUID.randomUUID(), new AccountId("acc-1"), new InstrumentId("uid-1", null),
            false, 10, null, null, null, null);

    @Autowired
    private AccountingService accounting;
    @Autowired
    private BotOrderRepository orderRepo;
    @Autowired
    private MoneyLedgerRepository ledgerRepo;
    @Autowired
    private BotEventRepository eventRepo;
    @Autowired
    private ExchangeConnectionRepository connectionRepo;
    @Autowired
    private InstrumentRepository instrumentRepo;

    @AfterEach
    void cleanUp() {
        ledgerRepo.deleteAll(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false));
        orderRepo.deleteAll(orderRepo.findAll().stream().filter(o -> o.getBotId().equals(botId)).toList());
        eventRepo.deleteAll(eventRepo.findAll().stream().filter(e -> e.getBotId().equals(botId)).toList());
        instrumentRepo.deleteAll(instrumentRepo.findAllByInstrumentUid(ctx.instrumentId().primary()));
        connectionRepo.findById(ctx.connectionId()).ifPresent(connectionRepo::delete);
    }

    @Test
    void closedCycleProducesRealizedPnlAndDerivedCycleResult() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "100", "1.00");
        accounting.recordOrderState(ctx, buy);

        assertThat(accounting.summary(botId, false).realizedPnl())
                .as("Открытая позиция не является убытком сама по себе")
                .isEqualByComparingTo("0.00");

        BotOrderEntity sell = saveOrder(OrderSide.SELL, 6, "110", "1.10");
        accounting.recordOrderState(ctx, sell);

        var rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false);
        assertThat(rows).extracting(r -> r.getEntryType()).contains(LedgerEntryType.CYCLE_RESULT);
        assertThat(rows.stream()
                .filter(r -> r.getEntryType() == LedgerEntryType.CYCLE_RESULT)
                .findFirst()
                .orElseThrow()
                .getAmount())
                .isEqualByComparingTo("97.90");
        assertThat(accounting.summary(botId, false).realizedPnl())
                .isEqualByComparingTo("97.90");
        assertThat(accounting.summary(botId, false).paidCommission())
                .as("Производная строка результата цикла не должна повторно учитывать комиссию продажи")
                .isEqualByComparingTo("2.10");
    }

    @Test
    void runtimeLotSizeOverridesLegacyDefaultLotSize() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22", "0.11", 1);

        accounting.recordOrderState(ctx, buy);

        var trade = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).get(0);
        assertThat(trade.getLotSize()).isEqualTo(10);
        assertThat(trade.getGrossAmount()).isEqualByComparingTo("220");
        assertThat(trade.getAmount()).isEqualByComparingTo("-220.11");
    }

    @Test
    void gridReplacementMarkerDoesNotAffectAccountingTotals() {
        accounting.recordMarker(ctx, LedgerEntryType.GRID_REPLACED, "GRID поколение 2");

        var rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEntryType()).isEqualTo(LedgerEntryType.GRID_REPLACED);
        assertThat(rows.get(0).isAffectsCash()).isFalse();
        assertThat(accounting.summary(botId, false).cashFlow()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void summaryRepairsLegacyLedgerLotSizeFromInstrumentCatalog() {
        saveConnectionAndInstrument(10);
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22", "0.11", 1);
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(buy.getId())
                .clientOrderId(buy.getClientOrderId())
                .side(OrderSide.BUY)
                .gridLevel(6)
                .lots(1L)
                .lotSize(1)
                .price(new BigDecimal("22"))
                .grossAmount(new BigDecimal("22"))
                .commission(new BigDecimal("0.11"))
                .amount(new BigDecimal("-22.11"))
                .executedLotsCum(1L)
                .currency("rub")
                .build());

        var summary = accounting.summary(botId, false);

        var repaired = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).get(0);
        assertThat(repaired.getLotSize()).isEqualTo(10);
        assertThat(repaired.getGrossAmount()).isEqualByComparingTo("220");
        assertThat(repaired.getAmount()).isEqualByComparingTo("-220.11");
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("220.11");
        assertThat(summary.averageEntryPrice()).isEqualByComparingTo("22.011");
    }

    @Test
    void summaryRepairsLotAmountStoredAsPerSharePrice() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22.36", "0.11", 10);
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(buy.getId())
                .clientOrderId(buy.getClientOrderId())
                .side(OrderSide.BUY)
                .gridLevel(6)
                .lots(1L)
                .lotSize(10)
                .price(new BigDecimal("223.60"))
                .grossAmount(new BigDecimal("2236.00"))
                .commission(new BigDecimal("0.11"))
                .amount(new BigDecimal("-2236.11"))
                .executedLotsCum(1L)
                .currency("rub")
                .build());

        var summary = accounting.summary(botId, false);

        var repaired = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).get(0);
        assertThat(repaired.getPrice()).isEqualByComparingTo("22.36");
        assertThat(repaired.getGrossAmount()).isEqualByComparingTo("223.60");
        assertThat(repaired.getAmount()).isEqualByComparingTo("-223.71");
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("223.71");
    }

    @Test
    void summaryRepairsLegacyCycleResultFromTradeRows() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22.27", "0.11", 10);
        accounting.recordOrderState(ctx, buy);
        BotOrderEntity sell = saveOrder(OrderSide.SELL, 6, "22.36", "0.11", 10);
        accounting.recordOrderState(ctx, sell);

        MoneyLedgerEntity cycle = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).stream()
                .filter(row -> row.getEntryType() == LedgerEntryType.CYCLE_RESULT)
                .findFirst()
                .orElseThrow();
        cycle.setGrossAmount(new BigDecimal("2236.00"));
        cycle.setAmount(new BigDecimal("2013.08"));
        ledgerRepo.saveAndFlush(cycle);

        accounting.summary(botId, false);

        MoneyLedgerEntity repaired = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).stream()
                .filter(row -> row.getEntryType() == LedgerEntryType.CYCLE_RESULT)
                .findFirst()
                .orElseThrow();
        assertThat(repaired.getGrossAmount()).isEqualByComparingTo("223.60");
        assertThat(repaired.getCommission()).isEqualByComparingTo("0.11");
        assertThat(repaired.getAmount()).isEqualByComparingTo("0.68");
    }

    @Test
    void catalogCorrectsHistoricalLotSizeThatIsTooLarge() {
        saveConnectionAndInstrument(1);
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "50.85", "0.03", 10);
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(buy.getId())
                .clientOrderId(buy.getClientOrderId())
                .side(OrderSide.BUY)
                .gridLevel(6)
                .lots(1L)
                .lotSize(10)
                .price(new BigDecimal("50.85"))
                .grossAmount(new BigDecimal("508.50"))
                .commission(new BigDecimal("0.03"))
                .amount(new BigDecimal("-508.53"))
                .executedLotsCum(1L)
                .currency("rub")
                .build());

        var summary = accounting.summary(botId, false);

        var repairedOrder = orderRepo.findById(buy.getId()).orElseThrow();
        var repairedLedger = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).get(0);
        assertThat(repairedOrder.getLotSize()).isEqualTo(1);
        assertThat(repairedLedger.getLotSize()).isEqualTo(1);
        assertThat(repairedLedger.getGrossAmount()).isEqualByComparingTo("50.85");
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("50.88");
    }

    @Test
    void buyCommissionCorrectionIsAllocatedBetweenSoldAndOpenLots() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "100", "0.20");
        buy.setRequestedLots(2);
        buy.setExecutedLots(2);
        buy.setFeeActual(false);
        orderRepo.save(buy);
        accounting.recordOrderState(ctx, buy);

        buy.setFee(new BigDecimal("0.14"));
        buy.setFeeActual(true);
        orderRepo.save(buy);
        accounting.recordOrderState(ctx, buy);

        BotOrderEntity sell = saveOrder(OrderSide.SELL, 6, "110", "1.10");
        accounting.recordOrderState(ctx, sell);

        var summary = accounting.summary(botId, false);
        assertThat(summary.openLots()).isEqualTo(1);
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("1000.07");
        assertThat(summary.realizedPnl()).isEqualByComparingTo("98.83");
    }

    @Test
    @Transactional
    void duplicateLedgerInsertDoesNotPoisonCallerTransaction() {
        UUID orderId = UUID.randomUUID();

        assertThat(accounting.saveIdempotent(ledgerEntry(orderId))).isTrue();
        assertThat(accounting.saveIdempotent(ledgerEntry(orderId))).isFalse();

        // Этот save и следующий запрос выполняются в исходной транзакции. До исправления
        // она падала здесь с "MoneyLedgerEntity has a null identifier".
        accounting.recordMarker(ctx, LedgerEntryType.GRID_REPLACED, "После дубликата");
        assertThat(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false)).hasSize(2);
    }

    private MoneyLedgerEntity ledgerEntry(UUID orderId) {
        return MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(orderId)
                .lots(1L)
                .lotSize(ctx.lotSize())
                .price(new BigDecimal("100"))
                .grossAmount(new BigDecimal("1000"))
                .commission(BigDecimal.ZERO)
                .amount(new BigDecimal("-1000"))
                .executedLotsCum(1L)
                .currency("rub")
                .build();
    }

    private BotOrderEntity saveOrder(OrderSide side, int level, String price, String fee) {
        return saveOrder(side, level, price, fee, ctx.lotSize());
    }

    private BotOrderEntity saveOrder(OrderSide side, int level, String price, String fee, int lotSize) {
        return orderRepo.save(BotOrderEntity.builder()
                .botId(botId)
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(UUID.randomUUID().toString())
                .side(side)
                .status(OrderStatus.FILLED)
                .gridLevel(level)
                .requestedLots(1)
                .executedLots(1)
                .limitPrice(new BigDecimal(price))
                .avgPrice(new BigDecimal(price))
                .fee(new BigDecimal(fee))
                .feeActual(true)
                .feeCurrency("rub")
                .lotSize(lotSize)
                .dryRun(false)
                .build());
    }

    private void saveConnectionAndInstrument(int lot) {
        connectionRepo.save(ExchangeConnectionEntity.builder()
                .id(ctx.connectionId())
                .exchange(ExchangeType.T_INVEST)
                .name("test")
                .settings("{}")
                .active(true)
                .build());
        instrumentRepo.save(InstrumentEntity.builder()
                .exchange(ExchangeType.T_INVEST)
                .instrumentUid(ctx.instrumentId().primary())
                .kind(InstrumentKind.SHARE)
                .segment(MarketSegment.SPOT)
                .ticker("TEST")
                .name("Test share")
                .lot(lot)
                .currency("rub")
                .buyAvailable(true)
                .sellAvailable(true)
                .apiTradeAvailable(true)
                .active(true)
                .build());
    }
}
