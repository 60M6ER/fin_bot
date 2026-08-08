package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.BotAccountingDto;
import ru.larionov.backend.dto.MoneyLedgerDto;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;
import ru.larionov.backend.service.BotEventService;
import ru.larionov.backend.service.MoneyFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Slf4j
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
    private final BotEventService events;
    private final ApplicationEventPublisher publisher;

    /**
     * Записывает денежные строки по cumulative-состоянию ордера.
     * Повтор того же состояния безопасен: дельта считается от уже записанной книги,
     * а уникальный ключ в БД закрывает гонку между стримом и сверкой.
     */
    @Transactional
    public void recordOrderState(BotExecutionContext ctx, BotOrderEntity order) {
        // ДО проверки исполнения. Книгу правит не эта заявка, а бот целиком, и повод
        // заглянуть в неё может прийти только с пустой заявки: у бота, чьи сделки уже
        // урегулированы, сверка трогает лишь ЖИВЫЕ заявки, а у них исполнения нет.
        // Стояла эта строка ниже — и починка валюты не срабатывала ровно там, где была
        // нужна: бот торгует, а книга по-прежнему подписана монетой.
        boolean healed = healBookCurrency(ctx);

        if (order == null || nvl(order.getExecutedQuantity()).signum() <= 0 || order.getAvgPrice() == null) {
            if (healed) {
                publishLedgerChanged(ctx.botId(), ctx.dryRun());
            }
            return;
        }

        BigDecimal previousCum = nvl(ledgerRepo.maxExecutedQuantityCum(order.getId(), TRADE_TYPES));
        // compareTo, а не >: у BigDecimal равные числа с разным масштабом не равны по equals,
        // и наивное сравнение раз за разом дописывало бы в книгу нулевые дельты.
        if (order.getExecutedQuantity().compareTo(previousCum) > 0) {
            recordTradeDelta(ctx, order, previousCum);
        } else if (order.getExecutedQuantity().compareTo(previousCum) < 0) {
            reduceRecordedQuantity(order, previousCum);
        }
        recordCommissionCorrection(ctx, order);
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
                .exchangeLotSize(ctx.exchangeLotSize())
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
                inventory.openQuantity(),
                inventory.averageEntryPrice(),
                firstCurrency(rows)
        );
    }

    /** Снимок открытой позиции для риск-контроля и других внутренних потребителей. */
    @Transactional
    public Inventory inventory(UUID botId, boolean dryRun) {
        repairLedgerRows(botId, dryRun);
        return rebuildInventory(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun));
    }

    /**
     * Переводит непродаваемый хвост уровня из партий сетки в корзину пыли.
     *
     * Себестоимость не выдумывается: она берётся из тех же партий, из которых хвост
     * и остался, пропорционально изъятому. Именно поэтому пыль нельзя усреднять
     * с живой позицией — у каждого хвоста своя цена, и распродать их одной ценой
     * значило бы соврать о результате того цикла, из которого хвост пришёл.
     *
     * @param gridLevel уровень, на котором остался хвост
     * @param quantity  сколько именно перевести в пыль
     */
    @Transactional
    public void recordDust(BotExecutionContext ctx, Integer gridLevel, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return;
        }
        List<MoneyLedgerEntity> rows =
                ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(ctx.botId(), ctx.dryRun());
        BigDecimal cost = consumeCost(rebuildOpenParcels(rows), gridLevel, quantity);

        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(ctx.botId())
                .dryRun(ctx.dryRun())
                .entryType(LedgerEntryType.DUST)
                .affectsCash(false)
                .purpose(OrderPurpose.DUST)
                .gridLevel(gridLevel)
                .quantity(quantity)
                .price(cost.divide(quantity, 9, RoundingMode.HALF_UP))
                .grossAmount(cost)
                .amount(BigDecimal.ZERO)
                .exchangeLotSize(ctx.exchangeLotSize())
                .note("Непродаваемый хвост уровня %s: %s по себестоимости %s"
                        .formatted(gridLevel, quantity.stripTrailingZeros().toPlainString(),
                                cost.stripTrailingZeros().toPlainString()))
                .build());
        publishLedgerChanged(ctx.botId(), ctx.dryRun());
    }

    /**
     * Сколько с каждого уровня переведено в пыль за всю жизнь бота.
     *
     * Именно за всю жизнь, а не «сколько ещё не продано»: заявки уровня остаются
     * в журнале ордеров навсегда, и уровневый учёт вычитает отсюда ровно то,
     * что с него было изъято. Продажа пыли уровня не касается — у неё его нет.
     */
    @Transactional(readOnly = true)
    public Map<Integer, BigDecimal> dustByLevel(UUID botId, boolean dryRun) {
        Map<Integer, BigDecimal> result = new HashMap<>();
        for (MoneyLedgerEntity row : ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun)) {
            if (row.getEntryType() == LedgerEntryType.DUST && row.getGridLevel() != null) {
                result.merge(row.getGridLevel(), nvl(row.getQuantity()), BigDecimal::add);
            }
        }
        return result;
    }

    /**
     * Разовый ремонт: собирает пыль, осевшую ДО появления её учёта.
     *
     * Хвосты копились с первого дня работы бота, но записей о них в книге нет —
     * они так и лежат недоеденными остатками партий. Проход находит их и переводит
     * в корзину задним числом, со своей себестоимостью: она всё это время хранилась
     * в самих партиях, восстанавливать её не из чего не приходится.
     *
     * Критерий намеренно строже боевого: только остаток МЕЛЬЧЕ ШАГА КОЛИЧЕСТВА,
     * без учёта минимальной суммы заявки. Такой остаток невозможно продать ни при
     * каких условиях, а вот остаток дешевле минимальной суммы вполне может быть
     * живой частью незакрытого цикла — и на старте отличить одно от другого нечем.
     *
     * Идемпотентен: собранное изымается из партий, и второй проход не находит ничего.
     *
     * @return сколько всего переведено в пыль
     */
    @Transactional
    public BigDecimal sweepUntradableRemainders(BotExecutionContext ctx) {
        BigDecimal step = ctx.quantityStep();
        if (step == null || step.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        List<MoneyLedgerEntity> rows =
                ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(ctx.botId(), ctx.dryRun());

        // По уровню целиком, а не по каждой партии: несколько крошек одного уровня
        // вместе вполне могут составить продаваемое количество, и тогда это не пыль.
        Map<Integer, BigDecimal> remainderByLevel = new HashMap<>();
        for (OpenParcel parcel : rebuildOpenParcels(rows)) {
            remainderByLevel.merge(parcel.gridLevel(), nvl(parcel.quantity()), BigDecimal::add);
        }

        BigDecimal swept = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> entry : remainderByLevel.entrySet()) {
            BigDecimal remainder = entry.getValue();
            if (entry.getKey() == null || remainder.signum() <= 0 || remainder.compareTo(step) >= 0) {
                continue;
            }
            recordDust(ctx, entry.getKey(), remainder);
            swept = swept.add(remainder);
        }
        return swept;
    }

    /** Сколько пыли накоплено и во сколько она обошлась. */
    @Transactional
    public DustBucket dust(UUID botId, boolean dryRun) {
        return rebuildDust(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun));
    }

    /**
     * Корзина пыли восстанавливается из книги, а не хранится отдельным счётчиком:
     * второй источник правды о деньгах рано или поздно разойдётся с первым.
     */
    private DustBucket rebuildDust(List<MoneyLedgerEntity> rows) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.DUST) {
                quantity = quantity.add(nvl(row.getQuantity()));
                cost = cost.add(nvl(row.getGrossAmount()));
            } else if (row.getEntryType() == LedgerEntryType.TRADE_SELL
                    && row.getPurpose() == OrderPurpose.DUST) {
                // Продали пыль: уходит и количество, и пропорциональная ему стоимость.
                BigDecimal sold = nvl(row.getQuantity()).min(quantity);
                if (sold.signum() <= 0) {
                    continue;
                }
                BigDecimal part = quantity.signum() == 0
                        ? BigDecimal.ZERO
                        : cost.multiply(sold).divide(quantity, 18, RoundingMode.HALF_UP);
                quantity = quantity.subtract(sold);
                cost = cost.subtract(part);
            }
        }
        return new DustBucket(quantity, cost);
    }

    @Transactional
    public List<MoneyLedgerDto> ledger(UUID botId, boolean dryRun) {
        repairLedgerRows(botId, dryRun);
        return ledgerRepo.findTop200ByBotIdAndDryRunOrderBySeqDesc(botId, dryRun).stream()
                .map(MoneyLedgerDto::of)
                .toList();
    }

    /**
     * Деньги, в которых ведётся книга, — валюта КОТИРОВКИ инструмента.
     *
     * Раньше сюда шла валюта комиссии ордера. У T-Invest это совпадало случайно:
     * комиссия рублёвая, котировка рублёвая. На Poloniex комиссия покупки удерживается
     * МОНЕТОЙ, и книга подписывалась «DOGE», хотя каждая сумма в ней — USDT.
     *
     * Портилась не только подпись. По валюте бота портфель решает, складывать ли его
     * P/L с остальными ({@code CurrencyCode.sameMoney}) и какую валюту считать основной
     * для подключения. Два бота на Poloniex получали «DOGE» и «ETH» и переставали
     * суммироваться между собой, хотя считают в одних и тех же долларах.
     *
     * Исходная валюта комиссии при этом не теряется: она осталась в {@code bot_order.fee_currency}
     * и по-прежнему показывает, что число получено пересчётом монеты в деньги.
     */
    /**
     * Подтягивает валюту старых строк к деньгам котировки.
     *
     * Одной записи новых строк мало: сводка берёт валюту из ПЕРВОЙ строки книги, и бот,
     * успевший поторговать до этой правки, до конца жизни показывал бы «DOGE» вместо
     * денег. Запрос трогает только расходящиеся строки, поэтому со второго раза он
     * не находит ничего и обходится бесплатно.
     */
    private boolean healBookCurrency(BotExecutionContext ctx) {
        String quote = ctx.quoteCurrency();
        if (quote == null || quote.isBlank()) {
            return false;
        }
        int healed = ledgerRepo.normalizeCurrency(ctx.botId(), ctx.dryRun(), quote);
        if (healed > 0) {
            log.info("Bot {}: валюта книги приведена к {} в {} строк(ах) — раньше там стояла "
                    + "валюта комиссии", ctx.botId(), quote, healed);
        }
        return healed > 0;
    }

    private static String bookCurrency(BotExecutionContext ctx, BotOrderEntity order) {
        String quote = ctx == null ? null : ctx.quoteCurrency();
        // Запасной вариант нужен ровно для старых записей и тестов, где котировка
        // неизвестна: он воспроизводит прежнее поведение, а не выдумывает валюту.
        return quote != null && !quote.isBlank() ? quote : order.getFeeCurrency();
    }

    private void recordTradeDelta(BotExecutionContext ctx, BotOrderEntity order, BigDecimal previousCum) {
        BigDecimal currentCum = order.getExecutedQuantity();
        BigDecimal delta = currentCum.subtract(previousCum);
        // Множителя больше нет: цена за единицу базового актива, количество — в них же.
        BigDecimal gross = order.getAvgPrice().multiply(delta);

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
                ? costBasisForSale(order.getBotId(), order.isDryRun(), order.getGridLevel(), delta)
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
                .purpose(order.getPurpose())
                .quantity(delta)
                .exchangeLotSize(order.getExchangeLotSize())
                .price(order.getAvgPrice())
                .grossAmount(gross)
                .commission(commissionDelta)
                .commissionEstimated(!order.isFeeActual())
                .amount(amount)
                .executedQuantityCum(currentCum)
                .currency(bookCurrency(ctx, order))
                .build());

        if (tradeSaved && order.getSide() == OrderSide.SELL) {
            recordCycleResult(ctx, order, delta, gross, commissionDelta, soldCost);
        }
    }

    /**
     * Уменьшает уже записанное количество, когда биржа подтвердила расчёт.
     *
     * Это НЕ откат сделки: объём торга не изменился, изменилось то, сколько монет
     * реально зачислено. Там, где комиссия удерживается из получаемой валюты
     * (Poloniex берёт её монетой), зачисляется строго меньше исполненного, и точная
     * величина приходит позже — со сделками заявки.
     *
     * Без этой правки книга навсегда осталась бы с брутто: строка пишется по первому
     * известию об исполнении, а дельты считаются от неё и только вверх. Позиция и
     * себестоимость расходились бы с журналом заявок на комиссию после каждой покупки —
     * и, что хуже, тихо: расхождение видно лишь в отчётах, а не в отказе биржи.
     *
     * Правится последняя строка ордера: именно её дельта и оказалась завышенной.
     */
    private void reduceRecordedQuantity(BotOrderEntity order, BigDecimal previousCum) {
        List<MoneyLedgerEntity> rows =
                ledgerRepo.findAllByOrderIdAndEntryTypeInOrderBySeqAsc(order.getId(), TRADE_TYPES);
        if (rows.isEmpty()) {
            return;
        }

        MoneyLedgerEntity last = rows.get(rows.size() - 1);
        BigDecimal shortfall = previousCum.subtract(order.getExecutedQuantity());
        BigDecimal corrected = nvl(last.getQuantity()).subtract(shortfall);
        if (corrected.signum() <= 0) {
            // Уценка съедает всю дельту целиком — такого не бывает при удержании
            // комиссии, и молча обнулять строку опаснее, чем оставить как есть.
            log.warn("Bot {}: подтверждённое количество по ордеру {} меньше записанной дельты — "
                            + "строку книги не трогаю",
                    order.getBotId(), order.getClientOrderId());
            return;
        }

        last.setQuantity(corrected);
        last.setExecutedQuantityCum(order.getExecutedQuantity());
        repairLedgerRowMoney(last);
        ledgerRepo.save(last);
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

            // Починки лотности здесь больше нет: сумма строки считается как price × quantity,
            // и множитель на неё не влияет. Раньше неверная лотность искажала деньги —
            // ради этого проход и был написан.

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
                || row.getQuantity() == null) {
            return true;
        }

        BigDecimal gross = row.getPrice().multiply(row.getQuantity());
        BigDecimal commission = nvl(row.getCommission());
        row.setGrossAmount(gross);
        row.setAmount(row.getEntryType() == LedgerEntryType.TRADE_BUY
                ? gross.negate().subtract(commission)
                : gross.subtract(commission));
        return true;
    }

    private boolean repairCycleResults(List<MoneyLedgerEntity> rows) {
        Map<String, ClosedSale> salesByFill = new HashMap<>();
        Deque<OpenParcel> parcels = new ArrayDeque<>();
        Map<UUID, BigDecimal> remainingBuyCorrections = commissionCorrections(rows, OrderSide.BUY);
        Map<UUID, BigDecimal> remainingBuyQuantity = tradeQuantities(rows, OrderSide.BUY);
        Map<UUID, BigDecimal> remainingSellCorrections = commissionCorrections(rows, OrderSide.SELL);
        Map<UUID, BigDecimal> remainingSellQuantity = tradeQuantities(rows, OrderSide.SELL);
        boolean changed = false;

        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.TRADE_BUY) {
                BigDecimal cost = nvl(row.getGrossAmount())
                        .add(nvl(row.getCommission()))
                        .add(takeCommissionCorrection(row, remainingBuyCorrections, remainingBuyQuantity));
                parcels.addLast(new OpenParcel(row.getGridLevel(), nvl(row.getQuantity()), cost));
            } else if (row.getEntryType() == LedgerEntryType.TRADE_SELL) {
                BigDecimal soldCost = consumeCost(parcels, row.getGridLevel(), nvl(row.getQuantity()));
                BigDecimal commission = nvl(row.getCommission())
                        .add(takeCommissionCorrection(row, remainingSellCorrections, remainingSellQuantity));
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

    /**
     * Ключ конкретного исполнения. Масштаб нормализуем: 1 и 1.0000000000 — одно
     * исполнение, а как строки они разные, и результат цикла не нашёл бы свою продажу.
     */
    private String fillKey(MoneyLedgerEntity row) {
        BigDecimal cum = row.getExecutedQuantityCum();
        return row.getOrderId() + ":" + (cum == null ? "—" : cum.stripTrailingZeros().toPlainString());
    }

    private void recordCommissionCorrection(BotExecutionContext ctx, BotOrderEntity order) {
        if (order.getFee() == null || nvl(order.getExecutedQuantity()).signum() <= 0) {
            return;
        }
        BigDecimal already = nvl(ledgerRepo.sumCommission(order.getId(), COMMISSION_TYPES));
        BigDecimal correction = order.getFee().subtract(already);
        if (correction.signum() == 0) {
            return;
        }
        BigDecimal executedCum = order.getExecutedQuantity();
        if (ledgerRepo.existsByOrderIdAndEntryTypeAndExecutedQuantityCum(
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
                .quantity(BigDecimal.ZERO)
                .exchangeLotSize(order.getExchangeLotSize())
                .commission(correction)
                .commissionEstimated(!order.isFeeActual())
                .amount(correction.negate())
                .executedQuantityCum(executedCum)
                .currency(bookCurrency(ctx, order))
                .note(order.isFeeActual() ? "Уточнение фактической комиссии" : "Уточнение оценочной комиссии")
                .build());
    }

    private void recordCycleResult(BotExecutionContext ctx, BotOrderEntity order, BigDecimal soldQuantity, BigDecimal gross,
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
                .quantity(soldQuantity)
                .exchangeLotSize(order.getExchangeLotSize())
                .grossAmount(gross)
                .commission(sellCommission)
                .commissionEstimated(!order.isFeeActual())
                .amount(result)
                .executedQuantityCum(order.getExecutedQuantity())
                .currency(bookCurrency(ctx, order))
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

    private BigDecimal costBasisForSale(UUID botId, boolean dryRun, Integer gridLevel, BigDecimal sold) {
        List<MoneyLedgerEntity> rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun);
        return consumeCost(rebuildOpenParcels(rows), gridLevel, sold);
    }

    private Deque<OpenParcel> rebuildOpenParcels(List<MoneyLedgerEntity> rows) {
        Deque<OpenParcel> parcels = new ArrayDeque<>();
        Map<UUID, BigDecimal> remainingBuyCorrections = commissionCorrections(rows, OrderSide.BUY);
        Map<UUID, BigDecimal> remainingBuyQuantity = tradeQuantities(rows, OrderSide.BUY);

        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.TRADE_BUY) {
                BigDecimal cost = nvl(row.getGrossAmount())
                        .add(nvl(row.getCommission()))
                        .add(takeCommissionCorrection(row, remainingBuyCorrections, remainingBuyQuantity));
                parcels.addLast(new OpenParcel(row.getGridLevel(), nvl(row.getQuantity()), cost));
            } else if (row.getEntryType() == LedgerEntryType.DUST) {
                // Хвост ушёл в корзину пыли: из партий сетки он изымается ровно так же,
                // как если бы его продали, — вместе со своей долей себестоимости.
                consumeCost(parcels, row.getGridLevel(), nvl(row.getQuantity()));
            } else if (row.getEntryType() == LedgerEntryType.TRADE_SELL
                    && row.getPurpose() != OrderPurpose.DUST) {
                // Продажа пыли партий сетки не касается: её товар давно из них изъят,
                // и списывать его отсюда во второй раз значило бы обнулить чужой уровень.
                consumeCost(parcels, row.getGridLevel(), nvl(row.getQuantity()));
            }
        }
        return parcels;
    }

    private Inventory rebuildInventory(List<MoneyLedgerEntity> rows) {
        BigDecimal openQuantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (OpenParcel parcel : rebuildOpenParcels(rows)) {
            openQuantity = openQuantity.add(parcel.quantity());
            cost = cost.add(parcel.costBasis());
        }
        BigDecimal avg = openQuantity.signum() == 0
                ? null
                : cost.divide(openQuantity, 9, RoundingMode.HALF_UP);
        return new Inventory(openQuantity, cost, avg);
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

    private Map<UUID, BigDecimal> tradeQuantities(List<MoneyLedgerEntity> rows, OrderSide side) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        LedgerEntryType tradeType = side == OrderSide.BUY
                ? LedgerEntryType.TRADE_BUY
                : LedgerEntryType.TRADE_SELL;
        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == tradeType && row.getOrderId() != null) {
                result.merge(row.getOrderId(), nvl(row.getQuantity()), BigDecimal::add);
            }
        }
        return result;
    }

    private BigDecimal takeCommissionCorrection(
            MoneyLedgerEntity row,
            Map<UUID, BigDecimal> remainingCorrections,
            Map<UUID, BigDecimal> remainingQuantity
    ) {
        UUID orderId = row.getOrderId();
        if (orderId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal correction = remainingCorrections.get(orderId);
        BigDecimal left = remainingQuantity.getOrDefault(orderId, BigDecimal.ZERO);
        BigDecimal rowQuantity = nvl(row.getQuantity());
        if (correction == null || correction.signum() == 0
                || left.signum() <= 0 || rowQuantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal allocated = rowQuantity.compareTo(left) >= 0
                ? correction
                : correction.multiply(rowQuantity).divide(left, 18, RoundingMode.HALF_UP);
        remainingCorrections.put(orderId, correction.subtract(allocated));
        BigDecimal quantityLeft = left.subtract(rowQuantity);
        remainingQuantity.put(orderId, quantityLeft.signum() < 0 ? BigDecimal.ZERO : quantityLeft);
        return allocated;
    }

    private BigDecimal consumeCost(Deque<OpenParcel> parcels, Integer gridLevel, BigDecimal toSell) {
        ConsumeResult result = consumeMatching(parcels, gridLevel, toSell);
        BigDecimal cost = result.cost();
        if (result.left().signum() > 0) {
            cost = cost.add(consumeMatching(parcels, null, result.left()).cost());
        }
        return cost;
    }

    private ConsumeResult consumeMatching(Deque<OpenParcel> parcels, Integer gridLevel, BigDecimal toSell) {
        if (toSell == null || toSell.signum() <= 0) {
            return new ConsumeResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Deque<OpenParcel> rebuilt = new ArrayDeque<>();
        BigDecimal left = toSell;
        BigDecimal cost = BigDecimal.ZERO;
        while (!parcels.isEmpty()) {
            OpenParcel parcel = parcels.removeFirst();
            boolean match = gridLevel == null || Objects.equals(gridLevel, parcel.gridLevel());
            if (!match || left.signum() <= 0) {
                rebuilt.addLast(parcel);
                continue;
            }
            BigDecimal taken = left.min(parcel.quantity());
            left = left.subtract(taken);
            cost = cost.add(parcel.take(taken).costBasis());
            OpenParcel rest = parcel.remainingAfter(taken);
            if (rest != null) {
                rebuilt.addLast(rest);
            }
        }
        parcels.addAll(rebuilt);
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

    private record ConsumeResult(BigDecimal left, BigDecimal cost) {
    }

    private record ClosedSale(BigDecimal gross, BigDecimal cost, BigDecimal commission) {
    }
}
