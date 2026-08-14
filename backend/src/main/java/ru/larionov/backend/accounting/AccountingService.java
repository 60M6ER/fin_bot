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
import ru.larionov.backend.enums.GridRole;
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
     * Списывает плату за перенос непокрытой позиции.
     *
     * Деньги двигает, партии — нет. Это принципиально: перенос есть реализованная
     * издержка удержания, а не часть себестоимости позиции. Попади он в партии,
     * и точка безубытка отъезжала бы каждую ночь сама собой, без единой сделки.
     * Партий не касаясь, запись оставляет верным тождество
     * {@code realizedPnl = cashFlow + costBasisOpen}.
     *
     * Идемпотентна в пределах суток: повторный проход (рестарт, ручной запуск,
     * дважды сработавший планировщик) ничего не спишет второй раз. Списать перенос
     * дважды — это не «неточность в отчёте», а выдуманный убыток, который потом
     * не отличить от настоящего.
     *
     * @return true, если списание действительно записано
     */
    @Transactional
    public boolean recordCarryFee(UUID botId, boolean dryRun, BigDecimal amount,
                                  BigDecimal notional, String currency, String note) {
        if (amount == null || amount.signum() <= 0) {
            return false;
        }
        // Двадцать часов, а не календарные сутки: проход запускается по расписанию,
        // и окно должно пережить и сдвиг запуска, и перезапуск приложения, не дав
        // при этом списать дважды за одну ночь.
        java.time.Instant window = java.time.Instant.now().minus(java.time.Duration.ofHours(20));
        if (ledgerRepo.existsByBotIdAndDryRunAndEntryTypeAndTsAfter(
                botId, dryRun, LedgerEntryType.CARRY_FEE, window)) {
            return false;
        }

        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(dryRun)
                .entryType(LedgerEntryType.CARRY_FEE)
                .affectsCash(true)
                .quantity(BigDecimal.ZERO)
                .grossAmount(notional)
                .exchangeLotSize(BigDecimal.ONE)
                // Со знаком минус: перенос уменьшает деньги бота.
                .amount(amount.negate())
                .currency(currency)
                .note(note)
                .build());
        publishLedgerChanged(botId, dryRun);
        return true;
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
                firstCurrency(rows),
                inventory.shortQuantity()
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
        //
        // Короткие партии сюда не попадают вовсе. Пыль — это непродаваемый хвост
        // КУПЛЕННОГО: он не портится и может ждать своей цены. У шорта хвост ведёт
        // себя ровно наоборот — он не ждёт, а копит плату за перенос, и «собрать
        // его в корзину» значило бы спрятать растущее обязательство.
        Map<Integer, BigDecimal> remainderByLevel = new HashMap<>();
        for (OpenParcel parcel : rebuildOpenParcels(rows)) {
            if (!parcel.isLong()) {
                continue;
            }
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

        // Денежный эффект сделки. Он же — то, из чего считается результат цикла:
        // закрытие приносит ровно эти деньги, а стоило оно съеденной себестоимости.
        BigDecimal amount = order.getSide() == OrderSide.BUY
                ? gross.negate().subtract(commissionDelta)
                : gross.subtract(commissionDelta);

        // Закрытие определяется РОЛЬЮ, а не стороной: в шорте цикл закрывает покупка.
        // Для лонга роль совпадает со стороной, поэтому ветка ведёт себя как прежде.
        boolean closes = order.getGridRole() == GridRole.CLOSE
                || (order.getGridRole() == null && order.getSide() == OrderSide.SELL);
        BigDecimal closedCost = closes
                ? costBasisForClose(order.getBotId(), order.isDryRun(), order.getGridLevel(), delta,
                        order.getSide() == OrderSide.BUY ? -1 : +1,
                        isHedgePurpose(order.getPurpose()))
                : BigDecimal.ZERO;

        boolean tradeSaved = saveIdempotent(MoneyLedgerEntity.builder()
                .botId(order.getBotId())
                .dryRun(order.isDryRun())
                .entryType(order.getSide() == OrderSide.BUY ? LedgerEntryType.TRADE_BUY : LedgerEntryType.TRADE_SELL)
                .affectsCash(true)
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .gridRole(order.getGridRole())
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

        if (tradeSaved && closes) {
            recordCycleResult(ctx, order, delta, gross, commissionDelta, amount, closedCost);
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
                .gridRole(order.getGridRole())
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

    /**
     * Результат закрытого цикла.
     *
     * Формула одна на оба направления: <b>деньги закрывающей сделки минус
     * себестоимость съеденных партий</b>. Для лонга это привычное
     * {@code выручка − комиссия − себестоимость}; для шорта — {@code полученное при
     * открытии − потраченное на откуп − комиссия}, и получается оно само собой,
     * потому что себестоимость шортовой партии отрицательна.
     *
     * @param closingAmount денежный эффект закрывающей сделки, уже за вычетом комиссии
     * @param closedCost    себестоимость закрытых партий, знаковая
     */
    private void recordCycleResult(BotExecutionContext ctx, BotOrderEntity order,
                                   BigDecimal soldQuantity, BigDecimal gross,
                                   BigDecimal commission, BigDecimal closingAmount,
                                   BigDecimal closedCost) {
        BigDecimal result = closingAmount.subtract(closedCost);
        boolean saved = saveIdempotent(MoneyLedgerEntity.builder()
                .botId(order.getBotId())
                .dryRun(order.isDryRun())
                .entryType(LedgerEntryType.CYCLE_RESULT)
                .affectsCash(false)
                .orderId(order.getId())
                .clientOrderId(order.getClientOrderId())
                .side(order.getSide())
                .gridRole(order.getGridRole())
                .gridLevel(order.getGridLevel())
                .quantity(soldQuantity)
                .exchangeLotSize(order.getExchangeLotSize())
                .grossAmount(gross)
                .commission(commission)
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

    private BigDecimal costBasisForClose(UUID botId, boolean dryRun, Integer gridLevel,
                                         BigDecimal closed, int expectedSign, boolean hedge) {
        List<MoneyLedgerEntity> rows = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun);
        return consumeCost(rebuildOpenParcels(rows), gridLevel, closed, expectedSign, hedge);
    }

    private Deque<OpenParcel> rebuildOpenParcels(List<MoneyLedgerEntity> rows) {
        Deque<OpenParcel> parcels = new ArrayDeque<>();
        Map<UUID, BigDecimal> remainingBuyCorrections = commissionCorrections(rows, OrderSide.BUY);
        Map<UUID, BigDecimal> remainingBuyQuantity = tradeQuantities(rows, OrderSide.BUY);

        for (MoneyLedgerEntity row : rows) {
            if (row.getEntryType() == LedgerEntryType.DUST) {
                // Хвост ушёл в корзину пыли: из партий сетки он изымается ровно так же,
                // как если бы его продали, — вместе со своей долей себестоимости.
                consumeCost(parcels, row.getGridLevel(), nvl(row.getQuantity()), +1);
                continue;
            }
            if (!TRADE_TYPES.contains(row.getEntryType())) {
                continue;
            }
            // Продажа пыли партий сетки не касается: её товар давно из них изъят,
            // и списывать его отсюда во второй раз значило бы обнулить чужой уровень.
            if (row.getPurpose() == OrderPurpose.DUST) {
                continue;
            }

            boolean buy = row.getEntryType() == LedgerEntryType.TRADE_BUY;
            BigDecimal quantity = nvl(row.getQuantity());
            if (quantity.signum() <= 0) {
                continue;
            }

            /*
             * Переворот — единственная сделка, которая закрывает ЧУЖИЕ партии.
             *
             * Одной заявкой он делает две вещи: гасит позицию сетки и на остаток
             * открывает плечо. Роль у неё «открывающая» — по отношению к плечу это
             * правда, — и раньше этого было достаточно, чтобы вся заявка целиком
             * легла новой короткой партией, а партии сетки остались висеть.
             *
             * Что из этого выходило, видно на боевом MAGN 14.08.2026: продали 560
             * при 140 в лонге, книга получила 560 коротких И 140 длинных сразу.
             * Нетто верное (−420), а разбивка врёт: средней цены входа у смешанной
             * книги нет вовсе, занятое обеспечение считается от 560 вместо 420,
             * и убыток закрытой части не признан — он сидит в открытой себестоимости.
             */
            boolean flip = isHedgePurpose(row.getPurpose()) && opens(row);

            if (opens(row) && !flip) {
                parcels.addLast(openedParcel(row, buy, quantity,
                        remainingBuyCorrections, remainingBuyQuantity));
                continue;
            }

            // Закрытие съедает партии ПРОТИВОПОЛОЖНОЙ стороны: продажа гасит длинные,
            // покупка — короткие. Без этого условия закрывающая покупка шорта съела бы
            // длинную партию соседнего уровня и обнулила чужой незакрытый цикл.
            int expectedSign = buy ? -1 : +1;
            // Плечо закрывает свои партии, сетка — свои: у них раздельный учёт.
            // Переворот — исключение ровно потому, что он и есть переход от одних
            // к другим: гасит партии СЕТКИ, а остаток станет партией плеча.
            boolean hedge = !flip && isHedgePurpose(row.getPurpose());
            BigDecimal left = consumeMagnitude(parcels, row.getGridLevel(), quantity,
                    expectedSign, hedge);

            // Сделка прошла ЧЕРЕЗ ноль: закрыла всё, что было, и на остаток открыла
            // позицию в другую сторону. Так выглядит переворот позиции — продажа
            // четырёх объёмов при одном купленном. Остаток обязан стать новой партией:
            // молча его отбросив, книга потеряла бы саму позицию.
            if (left.signum() > 0) {
                parcels.addLast(flippedParcel(row, buy, quantity, left,
                        remainingBuyCorrections, remainingBuyQuantity));
            }
        }
        return parcels;
    }

    /** Строка принадлежит восстановительному плечу, а не сетке. */
    private static boolean isHedgePurpose(OrderPurpose purpose) {
        return purpose == OrderPurpose.HEDGE || purpose == OrderPurpose.RECOVERY;
    }

    /**
     * Открывает ли строка книги позицию.
     *
     * По РОЛИ, а не по стороне: в шорте позицию набирает продажа. У строк, записанных
     * до появления роли, её проставила миграция по лонговому правилу, так что ответ
     * на них тот же, каким был всегда.
     */
    private static boolean opens(MoneyLedgerEntity row) {
        GridRole role = row.getGridRole();
        if (role != null) {
            return role == GridRole.OPEN;
        }
        return row.getEntryType() == LedgerEntryType.TRADE_BUY;
    }

    /**
     * Партия, открытая этой сделкой.
     *
     * Себестоимость есть МИНУС денежный эффект сделки. Для покупки это потраченное
     * вместе с комиссией (как и было всегда), для продажи — полученное за вычетом
     * комиссии, взятое с минусом.
     */
    private OpenParcel openedParcel(MoneyLedgerEntity row, boolean buy, BigDecimal quantity,
                                    Map<UUID, BigDecimal> corrections,
                                    Map<UUID, BigDecimal> quantities) {
        BigDecimal fees = nvl(row.getCommission())
                .add(takeCommissionCorrection(row, corrections, quantities));
        BigDecimal cost = buy
                ? nvl(row.getGrossAmount()).add(fees)
                : nvl(row.getGrossAmount()).subtract(fees).negate();
        return new OpenParcel(row.getGridLevel(), buy ? quantity : quantity.negate(), cost,
                row.getPurpose());
    }

    /** Часть сделки, ушедшая за ноль и открывшая позицию в другую сторону. */
    private OpenParcel flippedParcel(MoneyLedgerEntity row, boolean buy, BigDecimal quantity,
                                     BigDecimal left,
                                     Map<UUID, BigDecimal> corrections,
                                     Map<UUID, BigDecimal> quantities) {
        OpenParcel whole = openedParcel(row, buy, quantity, corrections, quantities);
        // Себестоимость делим пропорционально: за ноль ушла только часть сделки.
        return whole.take(left);
    }

    private Inventory rebuildInventory(List<MoneyLedgerEntity> rows) {
        BigDecimal openQuantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal longQuantity = BigDecimal.ZERO;
        BigDecimal shortQuantity = BigDecimal.ZERO;
        for (OpenParcel parcel : rebuildOpenParcels(rows)) {
            openQuantity = openQuantity.add(parcel.quantity());
            cost = cost.add(parcel.costBasis());
            if (parcel.isLong()) {
                longQuantity = longQuantity.add(parcel.magnitude());
            } else {
                shortQuantity = shortQuantity.add(parcel.magnitude());
            }
        }
        // Средняя цена входа остаётся ПОЛОЖИТЕЛЬНОЙ и у шорта: количество и
        // себестоимость меняют знак согласованно, и он сокращается сам.
        // У смешанной книги её нет вовсе — усреднять длинную сторону с короткой
        // бессмысленно, и выдать здесь число значило бы соврать убедительно.
        BigDecimal avg = openQuantity.signum() == 0
                || (longQuantity.signum() > 0 && shortQuantity.signum() > 0)
                ? null
                : cost.divide(openQuantity, 9, RoundingMode.HALF_UP);
        return new Inventory(openQuantity, cost, avg, longQuantity, shortQuantity);
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

    private BigDecimal consumeCost(Deque<OpenParcel> parcels, Integer gridLevel, BigDecimal toClose) {
        return consumeCost(parcels, gridLevel, toClose, +1);
    }

    /**
     * @param expectedSign какие партии разрешено съедать: +1 длинные, −1 короткие.
     *                     Закрытие обязано гасить свою сторону и только её
     */
    private BigDecimal consumeCost(Deque<OpenParcel> parcels, Integer gridLevel,
                                   BigDecimal toClose, int expectedSign) {
        return consumeCost(parcels, gridLevel, toClose, expectedSign, false);
    }

    /** @param hedge закрывается плечо (true) или сетка (false) — партии у них раздельные */
    private BigDecimal consumeCost(Deque<OpenParcel> parcels, Integer gridLevel,
                                   BigDecimal toClose, int expectedSign, boolean hedge) {
        ConsumeResult result = consumeMatching(parcels, gridLevel, toClose, expectedSign, hedge);
        BigDecimal cost = result.cost();
        if (result.left().signum() > 0) {
            cost = cost.add(consumeMatching(parcels, null, result.left(), expectedSign, hedge).cost());
        }
        return cost;
    }

    /** Сколько закрыть не удалось: остаток означает переворот позиции через ноль. */
    private BigDecimal consumeMagnitude(Deque<OpenParcel> parcels, Integer gridLevel,
                                        BigDecimal toClose, int expectedSign, boolean hedge) {
        ConsumeResult first = consumeMatching(parcels, gridLevel, toClose, expectedSign, hedge);
        if (first.left().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return consumeMatching(parcels, null, first.left(), expectedSign, hedge).left();
    }

    private ConsumeResult consumeMatching(Deque<OpenParcel> parcels, Integer gridLevel,
                                          BigDecimal toClose, int expectedSign, boolean hedge) {
        if (toClose == null || toClose.signum() <= 0) {
            return new ConsumeResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        Deque<OpenParcel> rebuilt = new ArrayDeque<>();
        BigDecimal left = toClose;
        BigDecimal cost = BigDecimal.ZERO;
        while (!parcels.isEmpty()) {
            OpenParcel parcel = parcels.removeFirst();
            boolean sameSide = expectedSign >= 0 ? parcel.isLong() : !parcel.isLong();
            // Плечо и сетка живут раздельно: закрытие одного не вправе трогать партии
            // другого. Без этого условия закрытие плеча — а у него уровня нет, значит
            // подходит «любая партия» — съело бы себестоимость чужого цикла сетки.
            boolean sameOwner = hedge == parcel.isHedge();
            boolean match = sameSide && sameOwner
                    && (gridLevel == null || Objects.equals(gridLevel, parcel.gridLevel()));
            if (!match || left.signum() <= 0) {
                rebuilt.addLast(parcel);
                continue;
            }
            // По модулю: у короткой партии количество отрицательно, а закрываем мы
            // всегда какую-то положительную величину.
            BigDecimal taken = left.min(parcel.magnitude());
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
