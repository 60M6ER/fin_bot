package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.BotAccountingDto;
import ru.larionov.backend.dto.MoneyLedgerDto;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.entity.InstrumentEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.repository.InstrumentRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;
import ru.larionov.backend.service.BotEventService;
import ru.larionov.backend.service.MoneyFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccountingService {

    private static final List<LedgerEntryType> TRADE_TYPES = List.of(
            LedgerEntryType.TRADE_BUY, LedgerEntryType.TRADE_SELL);
    private static final List<LedgerEntryType> COMMISSION_TYPES = List.of(
            LedgerEntryType.TRADE_BUY, LedgerEntryType.TRADE_SELL, LedgerEntryType.COMMISSION_CORRECTION);

    private final MoneyLedgerRepository ledgerRepo;
    private final MoneyLedgerWriter ledgerWriter;
    private final BotOrderRepository orderRepo;
    private final ExchangeConnectionRepository connectionRepo;
    private final InstrumentRepository instrumentRepo;
    private final BotEventService events;
    private final ApplicationEventPublisher publisher;

    /**
     * Записывает денежные строки по cumulative-состоянию ордера.
     * Повтор того же состояния безопасен: дельта считается от уже записанной книги,
     * а уникальный ключ в БД закрывает гонку между стримом и сверкой.
     */
    @Transactional
    public void recordOrderState(BotExecutionContext ctx, BotOrderEntity order) {
        if (order == null || order.getExecutedLots() <= 0 || order.getAvgPrice() == null) {
            return;
        }

        long previousCum = Optional.ofNullable(ledgerRepo.maxExecutedLotsCum(order.getId(), TRADE_TYPES)).orElse(0L);
        if (order.getExecutedLots() > previousCum) {
            recordTradeDelta(ctx, order, previousCum);
        }
        recordCommissionCorrection(order);
        publishLedgerChanged(ctx.botId(), ctx.dryRun());
    }

    /** Добавляет производную запись стратегии, которая не меняет денежные итоги. */
    @Transactional
    public void recordMarker(BotExecutionContext ctx, LedgerEntryType type, String note) {
        if (type == null || type.affectsCash()) {
            throw new IllegalArgumentException("Маркер книги должен быть неденежным");
        }
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(ctx.botId())
                .dryRun(ctx.dryRun())
                .entryType(type)
                .affectsCash(false)
                .lotSize(ctx.lotSize())
                .note(note)
                .build());
        publishLedgerChanged(ctx.botId(), ctx.dryRun());
    }

    /**
     * Публикуем после коммита (слушатель подписан на AFTER_COMMIT), иначе кэш
     * успел бы наполниться из ещё не зафиксированного чтения.
     */
    private void publishLedgerChanged(UUID botId, boolean dryRun) {
        publisher.publishEvent(new LedgerChangedEvent(botId, dryRun));
    }

    @Transactional
    public BotAccountingDto summary(UUID botId, boolean dryRun) {
        repairLedgerRows(botId, dryRun);
        return computeSummary(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun), dryRun);
    }

    /**
     * Та же сводка, но БЕЗ ремонтного прохода — и потому без единой записи в БД.
     *
     * Нужна списку ботов, который фронтенд опрашивает раз в 4 секунды: обычный
     * summary() на каждого бота при каждом опросе означал бы поток UPDATE-ов по
     * money_ledger на живой торговой системе. readOnly здесь несущая конструкция,
     * а не украшение: она делает запись структурно невозможной.
     */
    @Transactional(readOnly = true)
    public BotAccountingDto summaryFast(UUID botId, boolean dryRun) {
        return computeSummary(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun), dryRun);
    }

    private BotAccountingDto computeSummary(List<MoneyLedgerEntity> rows, boolean dryRun) {
        Inventory inventory = rebuildInventory(rows);
        BigDecimal cashFlow = rows.stream()
                .filter(MoneyLedgerEntity::isAffectsCash)
                .map(MoneyLedgerEntity::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidCommission = rows.stream()
                .filter(row -> COMMISSION_TYPES.contains(row.getEntryType()))
                .map(MoneyLedgerEntity::getCommission)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal realizedPnl = cashFlow.add(inventory.costBasisOpen());

        return new BotAccountingDto(
                dryRun,
                cashFlow,
                inventory.costBasisOpen(),
                realizedPnl,
                paidCommission,
                inventory.openLots(),
                inventory.averageEntryPrice(),
                firstCurrency(rows),
                inventory.openShares()
        );
    }

    /** Снимок открытой позиции для риск-контроля и других внутренних потребителей. */
    @Transactional
    public Inventory inventory(UUID botId, boolean dryRun) {
        repairLedgerRows(botId, dryRun);
        return rebuildInventory(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun));
    }

    @Transactional
    public List<MoneyLedgerDto> ledger(UUID botId, boolean dryRun) {
        repairLedgerRows(botId, dryRun);
        return ledgerRepo.findTop200ByBotIdAndDryRunOrderBySeqDesc(botId, dryRun).stream()
                .map(MoneyLedgerDto::of)
                .toList();
    }

    private void recordTradeDelta(BotExecutionContext ctx, BotOrderEntity order, long previousCum) {
        long currentCum = order.getExecutedLots();
        long deltaLots = currentCum - previousCum;
        int lotSize = resolveLotSize(order, ctx);
        BigDecimal gross = order.getAvgPrice()
                .multiply(BigDecimal.valueOf(deltaLots))
                .multiply(BigDecimal.valueOf(lotSize));

        BigDecimal totalFee = nvl(order.getFee());
        BigDecimal alreadyCommission = nvl(ledgerRepo.sumCommission(order.getId(), COMMISSION_TYPES));
        BigDecimal commissionDelta = totalFee.subtract(alreadyCommission);
        if (commissionDelta.signum() < 0) {
            commissionDelta = BigDecimal.ZERO;
        }

        BigDecimal amount = order.getSide() == OrderSide.BUY
                ? gross.negate().subtract(commissionDelta)
                : gross.subtract(commissionDelta);
        BigDecimal soldCost = order.getSide() == OrderSide.SELL
                ? costBasisForSale(order.getBotId(), order.isDryRun(), order.getGridLevel(), deltaLots)
                : BigDecimal.ZERO;

        boolean tradeSaved = saveIdempotent(MoneyLedgerEntity.builder()
                .botId(order.getBotId())
                .dryRun(order.isDryRun())
                .entryType(order.getSide() == OrderSide.BUY ? LedgerEntryType.TRADE_BUY : LedgerEntryType.TRADE_SELL)
                .affectsCash(true)
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .gridLevel(order.getGridLevel())
                .lots(deltaLots)
                .lotSize(lotSize)
                .price(order.getAvgPrice())
                .grossAmount(gross)
                .commission(commissionDelta)
                .commissionEstimated(!order.isFeeActual())
                .amount(amount)
                .executedLotsCum(currentCum)
                .currency(order.getFeeCurrency())
                .build());

        if (tradeSaved && order.getSide() == OrderSide.SELL) {
            recordCycleResult(order, deltaLots, gross, commissionDelta, soldCost);
        }
    }

    private void repairLedgerRows(UUID botId, boolean dryRun) {
        List<MoneyLedgerEntity> rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun);
        if (rows.isEmpty()) {
            return;
        }

        Map<UUID, BotOrderEntity> orders = new HashMap<>();
        boolean changed = false;
        for (MoneyLedgerEntity row : rows) {
            if (row.getOrderId() == null) {
                continue;
            }

            BotOrderEntity order = orders.computeIfAbsent(row.getOrderId(),
                    id -> orderRepo.findById(id).orElse(null));
            if (order == null) {
                continue;
            }

            boolean rowChanged = false;
            int lotSize = resolveLotSize(order, null);
            if (row.getLotSize() != lotSize) {
                row.setLotSize(lotSize);
                rowChanged = true;
            }

            // Старый mapper OrderStateStream принимал стоимость лота за цену бумаги.
            // REST-сверка сохраняет в bot_order нормализованную среднюю цену, поэтому
            // ею можно безопасно восстановить общую сумму даже для частичных fill.
            if (TRADE_TYPES.contains(row.getEntryType())
                    && order.getAvgPrice() != null
                    && !sameAmount(row.getPrice(), order.getAvgPrice())) {
                row.setPrice(order.getAvgPrice());
                rowChanged = true;
            }

            if (rowChanged) {
                repairLedgerRowMoney(row);
                changed = true;
            }
        }

        changed |= repairCycleResults(rows);
        if (changed) {
            ledgerRepo.saveAll(rows);
            publishLedgerChanged(botId, dryRun);
        }
    }

    private boolean repairLedgerRowMoney(MoneyLedgerEntity row) {
        if (row.getEntryType() == LedgerEntryType.COMMISSION_CORRECTION) {
            row.setAmount(nvl(row.getCommission()).negate());
            return true;
        }
        if (!TRADE_TYPES.contains(row.getEntryType())
                || row.getPrice() == null
                || row.getLots() == null) {
            return true;
        }

        BigDecimal gross = row.getPrice()
                .multiply(BigDecimal.valueOf(row.getLots()))
                .multiply(BigDecimal.valueOf(Math.max(1, row.getLotSize())));
        BigDecimal commission = nvl(row.getCommission());
        row.setGrossAmount(gross);
        row.setAmount(row.getEntryType() == LedgerEntryType.TRADE_BUY
                ? gross.negate().subtract(commission)
                : gross.subtract(commission));
        return true;
    }

    private boolean repairCycleResults(List<MoneyLedgerEntity> rows) {
        Map<String, ClosedSale> salesByFill = new HashMap<>();
        Deque<OpenLot> lots = new ArrayDeque<>();
        Map<UUID, BigDecimal> remainingBuyCorrections = commissionCorrections(rows, OrderSide.BUY);
        Map<UUID, Long> remainingBuyLots = tradeLots(rows, OrderSide.BUY);
        Map<UUID, BigDecimal> remainingSellCorrections = commissionCorrections(rows, OrderSide.SELL);
        Map<UUID, Long> remainingSellLots = tradeLots(rows, OrderSide.SELL);
        boolean changed = false;

        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.TRADE_BUY) {
                BigDecimal cost = nvl(row.getGrossAmount())
                        .add(nvl(row.getCommission()))
                        .add(takeCommissionCorrection(row, remainingBuyCorrections, remainingBuyLots));
                lots.addLast(new OpenLot(row.getGridLevel(), nvl(row.getLots()), row.getLotSize(), cost));
            } else if (row.getEntryType() == LedgerEntryType.TRADE_SELL) {
                BigDecimal soldCost = consumeCost(lots, row.getGridLevel(), nvl(row.getLots()));
                BigDecimal commission = nvl(row.getCommission())
                        .add(takeCommissionCorrection(row, remainingSellCorrections, remainingSellLots));
                salesByFill.put(fillKey(row), new ClosedSale(
                        nvl(row.getGrossAmount()), soldCost, commission));
            } else if (row.getEntryType() == LedgerEntryType.CYCLE_RESULT) {
                ClosedSale sale = salesByFill.get(fillKey(row));
                if (sale != null) {
                    BigDecimal result = sale.gross().subtract(sale.cost()).subtract(sale.commission());
                    if (!sameAmount(row.getGrossAmount(), sale.gross())) {
                        row.setGrossAmount(sale.gross());
                        changed = true;
                    }
                    if (!sameAmount(row.getCommission(), sale.commission())) {
                        row.setCommission(sale.commission());
                        changed = true;
                    }
                    if (!sameAmount(row.getAmount(), result)) {
                        row.setAmount(result);
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    private String fillKey(MoneyLedgerEntity row) {
        return row.getOrderId() + ":" + row.getExecutedLotsCum();
    }

    private int resolveLotSize(BotOrderEntity order, BotExecutionContext ctx) {
        int orderLot = order == null ? 1 : Math.max(1, order.getLotSize());
        Optional<Integer> catalogLot = order == null ? Optional.empty() : resolveCatalogLotSize(order);
        int lotSize = ctx != null && ctx.lotSize() > 0
                ? ctx.lotSize()
                : catalogLot.orElse(orderLot);

        if (order != null && lotSize != order.getLotSize()) {
            order.setLotSize(lotSize);
            orderRepo.save(order);
        }

        return lotSize;
    }

    private Optional<Integer> resolveCatalogLotSize(BotOrderEntity order) {
        if (order.getInstrumentUid() == null || order.getInstrumentUid().isBlank()) {
            return Optional.empty();
        }

        Optional<Integer> byConnection = connectionRepo.findById(order.getConnectionId())
                .flatMap(conn -> instrumentRepo.findByExchangeAndInstrumentUid(
                        conn.getExchange(), order.getInstrumentUid()))
                .map(InstrumentEntity::getLot)
                .filter(lot -> lot > 0);
        if (byConnection.isPresent()) {
            return byConnection;
        }

        Set<Integer> lots = new HashSet<>();
        for (InstrumentEntity instrument : instrumentRepo.findAllByInstrumentUid(order.getInstrumentUid())) {
            if (instrument.getLot() > 0) {
                lots.add(instrument.getLot());
            }
        }
        return lots.size() == 1 ? Optional.of(lots.iterator().next()) : Optional.empty();
    }

    private void recordCommissionCorrection(BotOrderEntity order) {
        if (order.getFee() == null || order.getExecutedLots() <= 0) {
            return;
        }
        BigDecimal already = nvl(ledgerRepo.sumCommission(order.getId(), COMMISSION_TYPES));
        BigDecimal correction = order.getFee().subtract(already);
        if (correction.signum() == 0) {
            return;
        }
        long executedCum = order.getExecutedLots();
        if (ledgerRepo.existsByOrderIdAndEntryTypeAndExecutedLotsCum(
                order.getId(), LedgerEntryType.COMMISSION_CORRECTION, executedCum)) {
            return;
        }

        saveIdempotent(MoneyLedgerEntity.builder()
                .botId(order.getBotId())
                .dryRun(order.isDryRun())
                .entryType(LedgerEntryType.COMMISSION_CORRECTION)
                .affectsCash(true)
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .gridLevel(order.getGridLevel())
                .lots(0L)
                .lotSize(order.getLotSize())
                .commission(correction)
                .commissionEstimated(!order.isFeeActual())
                .amount(correction.negate())
                .executedLotsCum(executedCum)
                .currency(order.getFeeCurrency())
                .note(order.isFeeActual() ? "Уточнение фактической комиссии" : "Уточнение оценочной комиссии")
                .build());
    }

    private void recordCycleResult(BotOrderEntity order, long soldLots, BigDecimal gross,
                                   BigDecimal sellCommission, BigDecimal soldCost) {
        BigDecimal result = gross.subtract(soldCost).subtract(sellCommission);
        boolean saved = saveIdempotent(MoneyLedgerEntity.builder()
                .botId(order.getBotId())
                .dryRun(order.isDryRun())
                .entryType(LedgerEntryType.CYCLE_RESULT)
                .affectsCash(false)
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .gridLevel(order.getGridLevel())
                .lots(soldLots)
                .lotSize(order.getLotSize())
                .grossAmount(gross)
                .commission(sellCommission)
                .commissionEstimated(!order.isFeeActual())
                .amount(result)
                .executedLotsCum(order.getExecutedLots())
                .currency(order.getFeeCurrency())
                .note("Результат закрытого цикла")
                .build());
        if (saved) {
            events.emit(order.getBotId(), BotEventLevel.INFO, BotEventType.CYCLE_CLOSED,
                    "Завершён цикл сетки на уровне %s. P/L цикла = %s"
                            .formatted(order.getGridLevel(),
                                    MoneyFormat.signed(result, order.getFeeCurrency())));
        }
    }

    boolean saveIdempotent(MoneyLedgerEntity entry) {
        try {
            ledgerWriter.insert(entry);
            return true;
        } catch (DataIntegrityViolationException ignored) {
            // Стрим и сверка могли одновременно принести один cumulative fill.
            return false;
        }
    }

    private BigDecimal costBasisForSale(UUID botId, boolean dryRun, Integer gridLevel, long soldLots) {
        List<MoneyLedgerEntity> rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun);
        Deque<OpenLot> lots = openLotsBeforeSale(rows);
        return consumeCost(lots, gridLevel, soldLots);
    }

    private Deque<OpenLot> openLotsBeforeSale(List<MoneyLedgerEntity> rows) {
        return rebuildOpenLots(rows);
    }

    private Deque<OpenLot> rebuildOpenLots(List<MoneyLedgerEntity> rows) {
        Deque<OpenLot> lots = new ArrayDeque<>();
        Map<UUID, BigDecimal> remainingBuyCorrections = commissionCorrections(rows, OrderSide.BUY);
        Map<UUID, Long> remainingBuyLots = tradeLots(rows, OrderSide.BUY);

        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.TRADE_BUY) {
                BigDecimal cost = nvl(row.getGrossAmount())
                        .add(nvl(row.getCommission()))
                        .add(takeCommissionCorrection(row, remainingBuyCorrections, remainingBuyLots));
                lots.addLast(new OpenLot(row.getGridLevel(), nvl(row.getLots()), row.getLotSize(), cost));
            } else if (row.getEntryType() == LedgerEntryType.TRADE_SELL) {
                consumeCost(lots, row.getGridLevel(), nvl(row.getLots()));
            }
        }
        return lots;
    }

    private Inventory rebuildInventory(List<MoneyLedgerEntity> rows) {
        Deque<OpenLot> lots = rebuildOpenLots(rows);
        long openLots = 0;
        long openShares = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (OpenLot lot : lots) {
            openLots += lot.lots();
            openShares += lot.lots() * Math.max(1, lot.lotSize());
            cost = cost.add(lot.costBasis());
        }
        BigDecimal avg = openShares == 0
                ? null
                : cost.divide(BigDecimal.valueOf(openShares), 9, RoundingMode.HALF_UP);
        return new Inventory(openLots, cost, avg, openShares);
    }

    private Map<UUID, BigDecimal> commissionCorrections(
            List<MoneyLedgerEntity> rows,
            OrderSide side
    ) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.COMMISSION_CORRECTION
                    && row.getSide() == side
                    && row.getOrderId() != null) {
                result.merge(row.getOrderId(), nvl(row.getCommission()), BigDecimal::add);
            }
        }
        return result;
    }

    private Map<UUID, Long> tradeLots(List<MoneyLedgerEntity> rows, OrderSide side) {
        Map<UUID, Long> result = new HashMap<>();
        for (MoneyLedgerEntity row : rows) {
            LedgerEntryType tradeType = side == OrderSide.BUY
                    ? LedgerEntryType.TRADE_BUY
                    : LedgerEntryType.TRADE_SELL;
            if (row.getEntryType() == tradeType && row.getOrderId() != null) {
                result.merge(row.getOrderId(), nvl(row.getLots()), Long::sum);
            }
        }
        return result;
    }

    private BigDecimal takeCommissionCorrection(
            MoneyLedgerEntity row,
            Map<UUID, BigDecimal> remainingCorrections,
            Map<UUID, Long> remainingLots
    ) {
        UUID orderId = row.getOrderId();
        if (orderId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal correction = remainingCorrections.get(orderId);
        long lotsLeft = remainingLots.getOrDefault(orderId, 0L);
        long rowLots = nvl(row.getLots());
        if (correction == null || correction.signum() == 0 || lotsLeft <= 0 || rowLots <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal allocated = rowLots >= lotsLeft
                ? correction
                : correction.multiply(BigDecimal.valueOf(rowLots))
                        .divide(BigDecimal.valueOf(lotsLeft), 18, RoundingMode.HALF_UP);
        remainingCorrections.put(orderId, correction.subtract(allocated));
        remainingLots.put(orderId, Math.max(0, lotsLeft - rowLots));
        return allocated;
    }

    private BigDecimal consumeCost(Deque<OpenLot> lots, Integer gridLevel, long toSell) {
        ConsumeResult result = consumeMatching(lots, gridLevel, toSell);
        BigDecimal cost = result.cost();
        if (result.left() > 0) {
            cost = cost.add(consumeMatching(lots, null, result.left()).cost());
        }
        return cost;
    }

    private ConsumeResult consumeMatching(Deque<OpenLot> lots, Integer gridLevel, long toSell) {
        if (toSell <= 0) {
            return new ConsumeResult(0, BigDecimal.ZERO);
        }
        Deque<OpenLot> rebuilt = new ArrayDeque<>();
        long left = toSell;
        BigDecimal cost = BigDecimal.ZERO;
        while (!lots.isEmpty()) {
            OpenLot lot = lots.removeFirst();
            boolean match = gridLevel == null || Objects.equals(gridLevel, lot.gridLevel());
            if (!match || left <= 0) {
                rebuilt.addLast(lot);
                continue;
            }
            long taken = Math.min(left, lot.lots());
            left -= taken;
            cost = cost.add(lot.take(taken).costBasis());
            OpenLot rest = lot.remainingAfter(taken);
            if (rest != null) {
                rebuilt.addLast(rest);
            }
        }
        lots.addAll(rebuilt);
        return new ConsumeResult(left, cost);
    }

    private String firstCurrency(List<MoneyLedgerEntity> rows) {
        return rows.stream()
                .map(MoneyLedgerEntity::getCurrency)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static long nvl(Long value) {
        return value == null ? 0 : value;
    }

    private record ConsumeResult(long left, BigDecimal cost) {
    }

    private record ClosedSale(BigDecimal gross, BigDecimal cost, BigDecimal commission) {
    }
}
