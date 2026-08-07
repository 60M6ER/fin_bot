package ru.larionov.backend.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.BotEventRepository;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Учёт считает в ЕДИНИЦАХ БАЗОВОГО АКТИВА. Числа в тестах сохранены прежними:
 * там, где раньше было «1 лот при лотности 10», теперь стоит «10 штук», и все
 * денежные ожидания обязаны совпасть до копейки — это и есть проверка того, что
 * переход на дробное количество не сдвинул ни одной суммы.
 */
@SpringBootTest
class AccountingServiceTest {

    /** Заявочная единица инструмента: 10 штук в лоте. */
    private static final BigDecimal LOT = BigDecimal.TEN;

    private final UUID botId = UUID.randomUUID();
    private final BotExecutionContext ctx = new BotExecutionContext(
            botId, UUID.randomUUID(), new AccountId("acc-1"), new InstrumentId("uid-1", null),
            false, LOT, LOT, null, null, null, null, null);

    @Autowired
    private AccountingService accounting;
    @Autowired
    private BotOrderRepository orderRepo;
    @Autowired
    private MoneyLedgerRepository ledgerRepo;
    @Autowired
    private BotEventRepository eventRepo;

    @AfterEach
    void cleanUp() {
        ledgerRepo.deleteAll(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false));
        orderRepo.deleteAll(orderRepo.findAll().stream().filter(o -> o.getBotId().equals(botId)).toList());
        eventRepo.deleteAll(eventRepo.findAll().stream().filter(e -> e.getBotId().equals(botId)).toList());
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

    /** Сумма строки книги — это цена за единицу × количество, без всяких множителей. */
    @Test
    void ledgerAmountIsPriceTimesQuantity() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22", "0.11");

        accounting.recordOrderState(ctx, buy);

        var trade = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false).get(0);
        assertThat(trade.getQuantity()).isEqualByComparingTo("10");
        assertThat(trade.getGrossAmount()).isEqualByComparingTo("220");
        assertThat(trade.getAmount()).isEqualByComparingTo("-220.11");
    }

    /** Дробное количество: криптобиржевой случай проходит тот же путь, что и биржевой. */
    @Test
    void fractionalQuantityIsAccountedExactly() {
        BotOrderEntity buy = orderRepo.save(order(OrderSide.BUY, 0, "60000", "0.06")
                .requestedQuantity(new BigDecimal("0.000500"))
                .executedQuantity(new BigDecimal("0.000500"))
                .exchangeLotSize(BigDecimal.ONE)
                .build());

        accounting.recordOrderState(ctx, buy);

        var summary = accounting.summary(botId, false);
        assertThat(summary.openQuantity()).isEqualByComparingTo("0.0005");
        // 0.0005 × 60000 = 30, плюс комиссия 0.06
        assertThat(summary.costBasisOpen()).isEqualByComparingTo("30.06");
        assertThat(summary.averageEntryPrice()).isEqualByComparingTo("60120");
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

    /**
     * Старый mapper принимал стоимость лота за цену бумаги. Ремонтный проход обязан
     * это чинить и после перехода на количество: цена в книге — всегда за единицу.
     */
    @Test
    void summaryRepairsLotAmountStoredAsPerSharePrice() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22.36", "0.11");
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(buy.getId())
                .clientOrderId(buy.getClientOrderId())
                .side(OrderSide.BUY)
                .gridLevel(6)
                .quantity(BigDecimal.TEN)
                .exchangeLotSize(LOT)
                .price(new BigDecimal("223.60"))
                .grossAmount(new BigDecimal("2236.00"))
                .commission(new BigDecimal("0.11"))
                .amount(new BigDecimal("-2236.11"))
                .executedQuantityCum(BigDecimal.TEN)
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
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "22.27", "0.11");
        accounting.recordOrderState(ctx, buy);
        BotOrderEntity sell = saveOrder(OrderSide.SELL, 6, "22.36", "0.11");
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
    void buyCommissionCorrectionIsAllocatedBetweenSoldAndOpenParcels() {
        BotOrderEntity buy = saveOrder(OrderSide.BUY, 6, "100", "0.20");
        buy.setRequestedQuantity(new BigDecimal("20"));
        buy.setExecutedQuantity(new BigDecimal("20"));
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
        assertThat(summary.openQuantity()).isEqualByComparingTo("10");
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

    /**
     * Уникальный ключ книги живёт поверх numeric. Одно и то же исполнение, записанное
     * с разной шкалой (10 и 10.0000000000), обязано остаться ОДНОЙ строкой — иначе
     * стрим и сверка, принеся один fill одновременно, задвоили бы сделку в книге.
     */
    @Test
    @Transactional
    void duplicateIsDetectedRegardlessOfDecimalScale() {
        UUID orderId = UUID.randomUUID();

        assertThat(accounting.saveIdempotent(ledgerEntry(orderId, new BigDecimal("10")))).isTrue();
        assertThat(accounting.saveIdempotent(ledgerEntry(orderId, new BigDecimal("10.0000000000")))).isFalse();
    }

    private MoneyLedgerEntity ledgerEntry(UUID orderId) {
        return ledgerEntry(orderId, BigDecimal.TEN);
    }

    private MoneyLedgerEntity ledgerEntry(UUID orderId, BigDecimal executedCum) {
        return MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.TRADE_BUY)
                .affectsCash(true)
                .orderId(orderId)
                .quantity(BigDecimal.TEN)
                .exchangeLotSize(ctx.exchangeLotSize())
                .price(new BigDecimal("100"))
                .grossAmount(new BigDecimal("1000"))
                .commission(BigDecimal.ZERO)
                .amount(new BigDecimal("-1000"))
                .executedQuantityCum(executedCum)
                .currency("rub")
                .build();
    }

    /** Одна заявочная единица инструмента, то есть 10 штук. */
    /**
     * Книга ведётся в деньгах КОТИРОВКИ, а не в валюте комиссии.
     *
     * На Poloniex комиссия покупки удерживается монетой, и {@code fee_currency} у ордера —
     * «DOGE». Пока валюта книги бралась оттуда, вся доходность бота подписывалась монетой,
     * хотя каждая сумма в ней — USDT. Это ломало не только подпись: по валюте бота портфель
     * решает, складывать ли его P/L с остальными, и два бота одного подключения
     * («DOGE» и «ETH») переставали суммироваться.
     */
    @Test
    void bookIsDenominatedInQuoteMoneyEvenWhenTheFeeWasTakenInCoin() {
        BotExecutionContext poloniex = new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc-1"),
                new InstrumentId("POLONIEX:DOGE_USDT", null),
                false, BigDecimal.ONE, new BigDecimal("0.001"), null,
                null, null, null, null, "USDT");

        BotOrderEntity buy = orderRepo.save(order(OrderSide.BUY, 0, "0.070013", "0.019719")
                .requestedQuantity(new BigDecimal("140.826"))
                .executedQuantity(new BigDecimal("140.544348"))
                .exchangeLotSize(BigDecimal.ONE)
                // Комиссия удержана монетой — это по-прежнему видно у ордера.
                .feeCurrency("DOGE")
                .build());

        accounting.recordOrderState(poloniex, buy);

        assertThat(accounting.summary(botId, false).currency())
                .as("деньги книги — USDT, монета осталась лишь пометкой у комиссии")
                .isEqualTo("USDT");
        assertThat(buy.getFeeCurrency())
                .as("происхождение комиссии не теряем: по нему видно, что число пересчитано")
                .isEqualTo("DOGE");
    }

    /**
     * Строки, записанные до правки, подтягиваются к деньгам котировки.
     *
     * Сводка берёт валюту из ПЕРВОЙ строки книги, поэтому писать правильно только новые
     * было бы мало: бот, успевший поторговать раньше, до конца жизни показывал бы монету.
     */
    @Test
    void alreadyWrittenRowsAreHealedToQuoteMoney() {
        BotExecutionContext noCurrency = new BotExecutionContext(
                botId, UUID.randomUUID(), new AccountId("acc-1"),
                new InstrumentId("POLONIEX:DOGE_USDT", null),
                false, BigDecimal.ONE, new BigDecimal("0.001"), null, null, null, null, null);

        // Так писала прежняя версия: валюта книги = валюта комиссии.
        BotOrderEntity old = orderRepo.save(order(OrderSide.BUY, 0, "0.07", "0.01")
                .requestedQuantity(new BigDecimal("100"))
                .executedQuantity(new BigDecimal("100"))
                .exchangeLotSize(BigDecimal.ONE)
                .feeCurrency("DOGE")
                .build());
        accounting.recordOrderState(noCurrency, old);
        assertThat(accounting.summary(botId, false).currency()).isEqualTo("DOGE");

        // Бот перезапущен уже с известной котировкой — книга обязана выправиться.
        BotExecutionContext withCurrency = new BotExecutionContext(
                botId, noCurrency.connectionId(), new AccountId("acc-1"),
                new InstrumentId("POLONIEX:DOGE_USDT", null),
                false, BigDecimal.ONE, new BigDecimal("0.001"), null,
                null, null, null, null, "USDT");
        accounting.recordOrderState(withCurrency, old);

        assertThat(accounting.summary(botId, false).currency()).isEqualTo("USDT");
        assertThat(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false))
                .as("выправлена вся книга, а не только новые строки")
                .allSatisfy(row -> assertThat(row.getCurrency()).isEqualTo("USDT"));
    }

    private BotOrderEntity saveOrder(OrderSide side, int level, String price, String fee) {
        return orderRepo.save(order(side, level, price, fee)
                .requestedQuantity(LOT)
                .executedQuantity(LOT)
                .exchangeLotSize(LOT)
                .build());
    }

    private BotOrderEntity.BotOrderEntityBuilder order(OrderSide side, int level, String price, String fee) {
        return BotOrderEntity.builder()
                .botId(botId)
                .connectionId(ctx.connectionId())
                .accountId(ctx.accountId().value())
                .instrumentUid(ctx.instrumentId().primary())
                .clientOrderId(UUID.randomUUID().toString())
                .side(side)
                .status(OrderStatus.FILLED)
                .gridLevel(level)
                .limitPrice(new BigDecimal(price))
                .avgPrice(new BigDecimal(price))
                .fee(new BigDecimal(fee))
                .feeActual(true)
                .feeCurrency("rub")
                .dryRun(false);
    }
}
