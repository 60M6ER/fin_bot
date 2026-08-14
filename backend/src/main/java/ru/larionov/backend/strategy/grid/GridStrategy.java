package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.accounting.DustBucket;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.exchange.api.model.account.MarginAttributes;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.OrderBook;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.execution.PlaceIntent;
import ru.larionov.backend.execution.ReconcileResult;
import ru.larionov.backend.execution.RiskRejectedException;
import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.CommandRequest;
import ru.larionov.backend.strategy.StrategyCommand;
import ru.larionov.backend.strategy.StrategyContext;
import ru.larionov.backend.strategy.StrategySnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Сеточная стратегия, только в лонг.
 *
 * Логика: покупаем на уровне i, продаём на уровне i+1. Прибыль за цикл —
 * шаг сетки минус комиссия за оборот; отсюда и проверка при старте.
 *
 * Шортов нет намеренно: продаём только то, что перед этим купили. На брокерском
 * счёте без маржи иначе и нельзя, а с маржой риск перестаёт быть ограниченным.
 *
 * Два ограничения площадки определяют весь жизненный цикл:
 * <ul>
 *   <li>GTC у T-Invest нет — заявки живут одну сессию и на открытии выставляются заново;</li>
 *   <li>события стрима теряются при разрыве — после реконнекта сначала сверка, потом действия.</li>
 * </ul>
 */
@Slf4j
public class GridStrategy implements Strategy {

    /** Сколько сверок подряд должны показать расхождение, чтобы оно считалось настоящим. */
    private static final int MISMATCH_CONFIRMATIONS = 2;

    /**
     * Не final: бюджет меняется командой оператора без остановки бота. Подменяется
     * ЦЕЛИКОМ и только после успешной проверки нового размера — полуприменённой
     * конфигурации не бывает.
     */
    private GridConfig cfg;

    /**
     * Направление поколения: лонг покупает ниже и продаёт выше, шорт — зеркально.
     *
     * Берётся из конфигурации и внутри поколения не меняется. Смена направления —
     * это событие уровня перестановки сетки, а не решение отдельного прохода:
     * поменяй его посреди жизни поколения, и уже выставленные встречные заявки
     * стали бы закрывать не то, что открывали.
     */
    private GridDirection direction = GridDirection.LONG;

    private StrategyContext ctx;
    private GridLadder ladder;
    private GridRange activeRange;

    /**
     * Размер заявки по уровням и бюджет, от которого он посчитан.
     *
     * Пишутся ТОЛЬКО в трёх точках перестройки лесенки (старт, перестановка вверх,
     * перестановка вниз). Именно поэтому у бота с реинвестированием прибыли объём
     * заявки не «плывёт» между покупкой и её встречной продажей: обновление комиссий
     * идёт через revalidate с уже замороженным размером.
     */
    private GridSizing sizing;
    private BigDecimal activeBudget;
    private long gridGeneration;
    private boolean halted;
    private Instant upperBreakoutCandidateAt;
    private Instant lowerBreakoutCandidateAt;
    private boolean lowerBreakoutPaused;
    private boolean awaitingUpperReplacement;
    private boolean awaitingDownwardReplacement;
    private GridRange pendingDownwardRange;
    private int downwardReplacements;
    private BigDecimal realizedDownwardLoss = BigDecimal.ZERO;
    private BigDecimal downwardLossBaseline;

    /**
     * Перестановку запросил оператор кнопкой, а не подтверждённый пробой.
     *
     * Снимает ровно две проверки — потолок убытка и лимит числа перестановок,
     * то есть те, что выражают заранее заданное согласие на риск. Нажатие кнопки
     * и есть согласие, данное явно и на конкретную сумму, которую человек видит
     * перед собой. Проверки достоверности (позиция книги против биржевой) при этом
     * остаются: они не про деньги, а про то, знаем ли мы, чем торгуем.
     */
    private boolean forcedReplacement;

    /**
     * Оператор запросил плановую остановку: покупки прекращены, ждём распродажи.
     *
     * Отдельно от halted намеренно. halted — это авария: бот замер, и что делать
     * с позицией, решает человек. Здесь наоборот, решение уже принято и работа
     * продолжается — просто в одну сторону, до полного выхода из позиции.
     */
    private boolean stopScheduled;

    /** Остановка уже запрошена: просить её второй раз незачем. Живёт до конца процесса. */
    private boolean stopRequested;
    private Instant lastReplacementAt;
    private BigDecimal reconciledPosition;
    private volatile StrategySnapshot snapshot;

    /**
     * Бот остановлен: события, дошедшие после этого, игнорируются.
     *
     * volatile, потому что это единственное поле стратегии, которое пишется НЕ из
     * потока событийного цикла: остановку инициирует управляющий поток.
     */
    private volatile boolean stopped;

    /** Последняя известная цена: нужна, чтобы решать, где покупать, а где продавать. */
    private BigDecimal lastPrice;

    /**
     * Торгуются ли сейчас лимитные заявки.
     *
     * Источник правды — стрим статуса торгов, но он присылает событие только при
     * СМЕНЕ состояния. Поэтому при старте бот дополнительно спрашивает текущий статус
     * инструмента напрямую (см. {@link #seedTradingStatus()}): иначе бот, поднятый
     * посреди сессии, просидел бы без единого ордера до следующего перехода статуса,
     * то есть часами.
     */
    private boolean limitOrdersAvailable;

    /** Выход из диапазона вниз: покупки прекращены. */
    private boolean buyingStopped;

    /** Последний тарифный снимок, по которому проверялась прибыльность сетки. */
    private FeeInfo activeFees;
    private Instant nextFeeRefreshAt;
    private boolean blockedByFees;

    /**
     * Позиция журнала разошлась с биржевой. Пока расхождение не исчезнет,
     * бот не выставляет заявок: действовать, не зная своей позиции, — это ровно
     * то, из-за чего сетка продала больше, чем купила.
     */
    private boolean positionMismatched;

    /**
     * Сколько сверок подряд показали расхождение.
     *
     * Одной сверки мало. Журнал обновляется событием стрима сразу в момент сделки,
     * а расчётная позиция у брокера — асинхронно, и между этими моментами сверка видит
     * расхождение ровно на размер только что исполненной заявки. За торговый день это
     * дало полтора десятка ложных остановок с уведомлениями, каждая из которых
     * снималась сама на следующем же проходе.
     *
     * Настоящее расхождение никуда не девается и подтверждается вторым проходом,
     * поэтому задержка на одну сверку ничего не стоит по риску.
     */
    private int consecutiveMismatches;

    /**
     * Наценка сверх себестоимости и комиссии, с которой продаётся накопленная пыль.
     *
     * Пыль продаётся не ради заработка, а чтобы перестать быть пылью, — но и в убыток
     * отдавать её незачем: она не портится и вполне может подождать своей цены.
     */
    private static final BigDecimal DUST_SALE_MARGIN = new BigDecimal("0.001");

    /** Об остановке ликвидации сообщаем один раз за эпизод, а не каждый тик. */
    private boolean liquidationStallReported;

    /**
     * Идущий эпизод восстановительного плеча, если он есть.
     *
     * Пока он не null, на бирже висит непокрытая позиция, и всё знание о том, как из
     * неё выходить, живёт здесь и в сохранённом состоянии.
     */
    private HedgeEpisode hedgeEpisode;

    /** Сколько переворотов уже потрачено в текущем поколении. */
    private int hedgeEpisodesUsed;

    /**
     * Порог достаточности средств, ниже которого счёт считается подошедшим к краю.
     *
     * Полтора — это запас над единицей, при которой брокер объявляет маржин-колл.
     * Смысл запаса в том, чтобы человек узнал о приближении заранее, а не в момент,
     * когда решать уже поздно.
     */
    private static final BigDecimal MARGIN_WARNING_LEVEL = new BigDecimal("1.5");

    /** О нехватке обеспечения сообщаем раз за эпизод: тик частый, а событие громкое. */
    private boolean marginWarningReported;

    /** То же для перестановки вверх, застрявшей на незакрываемом остатке позиции. */
    private boolean upperReplacementStallReported;

    /** Уровни, о разъехавшемся учёте которых уже сообщили. */
    private final Set<Integer> negativeLevelsReported = new HashSet<>();

    /** То же для позиции, не покрытой ни одним уровнем: сообщаем раз за эпизод. */
    private boolean uncoveredPositionReported;

    /**
     * Бюджет изменился — выставленные покупки нужно привести к новому размеру.
     *
     * Флаг, а не проверка на каждом проходе: сравнивать размер каждой заявки с планом
     * постоянно означало бы риск вечной пары «снял — поставил» из-за любого расхождения
     * в округлении. Переразмер делается по факту команды и гаснет, когда сделан.
     */
    private boolean resizeRequested;

    /**
     * Уровни, которые биржа только что отказалась принимать, и до какого момента
     * их не трогать.
     *
     * Отказ отказу рознь, но снаружи они неотличимы, а последствие одно: без паузы
     * бот повторяет ту же заявку каждый проход. 09.08.2026 бот на MVID полдня бился
     * об «30099 The price is outside the limits for this instrument» — цена уровня 13
     * вышла за дневной коридор бумаги, и это не лечится повтором через минуту.
     * Каждая попытка оставляла запись PENDING, которую следом разбирала сверка.
     *
     * Пауза не вечная: коридор меняется вместе с рынком и заведомо другой в новой
     * сессии, поэтому счётчик сбрасывается и по времени, и при открытии торгов.
     */
    private final Map<String, Instant> placementCooldowns = new HashMap<>();

    public GridStrategy(GridConfig cfg) {
        this.cfg = cfg;
    }

    // ==============================
    // LIFECYCLE
    // ==============================

    @Override
    public void onStart(StrategyContext ctx) {
        onStart(ctx, null);
    }

    @Override
    public void onStart(StrategyContext ctx, ReconcileResult initialState) {
        this.ctx = ctx;

        if (!cfg.enabled()) {
            ctx.warn("GRID: стратегия выключена в конфигурации (enabled=false)");
            return;
        }
        this.direction = cfg.direction();
        TradingConstraints constraints = ctx.constraints();
        RangeResolution resolution = resolveActiveRange(initialState, constraints);
        GridStrategyState restored = resolution.state();
        this.activeRange = resolution.range();
        this.gridGeneration = resolution.generation();
        this.awaitingUpperReplacement = restored != null && restored.awaitingUpperReplacement();
        this.awaitingDownwardReplacement = restored != null && restored.awaitingDownwardReplacement();
        this.pendingDownwardRange = restored == null ? null : restored.pendingRange();
        this.downwardReplacements = restored == null ? 0 : restored.downwardReplacements();
        this.realizedDownwardLoss = restored == null
                ? BigDecimal.ZERO : restored.realizedDownwardLoss();
        this.downwardLossBaseline = restored == null ? null : restored.downwardLossBaseline();
        this.lastReplacementAt = restored == null ? null : restored.lastReplacementAt();
        this.forcedReplacement = restored != null && restored.forcedReplacement();
        this.stopScheduled = restored != null && restored.stopScheduled();
        this.hedgeEpisode = restored == null ? null : restored.hedgeEpisode();
        this.hedgeEpisodesUsed = restored == null ? 0 : restored.hedgeEpisodesUsed();
        /*
         * Направление берём из СОХРАНЁННОГО состояния, а из конфигурации — только если
         * сохранённого нет. Конфигурация задаёт направление СТАРТОВОЕ; дальше его
         * выбирает сам бот, разворачиваясь на неблагоприятных пробоях. Читай мы здесь
         * конфигурацию всегда, перезапуск после разворота поднял бы лонговую сетку
         * поверх открытой короткой позиции.
         */
        if (restored != null && restored.direction() != null) {
            this.direction = restored.direction();
        }
        requireShortIsAllowed();
        boolean restoredStateCleared = false;
        if (awaitingUpperReplacement
                && cfg.onUpperBreakout() != GridConfig.UpperBreakoutAction.REPLACE_UPPER) {
            awaitingUpperReplacement = false;
            restoredStateCleared = true;
        }
        if (awaitingDownwardReplacement
                && cfg.onRangeExit() != GridConfig.RangeExitAction.REPLACE_LOWER) {
            awaitingDownwardReplacement = false;
            pendingDownwardRange = null;
            downwardLossBaseline = null;
            restoredStateCleared = true;
        }
        if (awaitingDownwardReplacement && pendingDownwardRange == null) {
            throw new IllegalStateException(
                    "Сохранено ожидание перестановки вниз без подготовленного диапазона");
        }
        if (forcedReplacement && !awaitingDownwardReplacement) {
            // Ручное разрешение живёт ровно столько, сколько идёт запрошенная им
            // ликвидация. Пережившее её означало бы тихо снятый потолок убытка
            // у следующей — уже автоматической — перестановки.
            forcedReplacement = false;
            restoredStateCleared = true;
        }
        this.buyingStopped = awaitingUpperReplacement || awaitingDownwardReplacement || stopScheduled;
        this.lastPrice = resolution.referencePrice();
        // Цена пришла REST-запросом оценщика диапазона, а не стримом. Сообщаем её
        // наружу: иначе бот торгует по ней, а рыночная оценка позиции пустует.
        if (this.lastPrice != null) {
            ctx.observedPrice(this.lastPrice, ctx.clock().instant());
        }
        this.ladder = GridLadder.build(activeRange, constraints.minPriceIncrement());

        FeeInfo fees = resolveFeeInfo();
        activeFees = fees;
        nextFeeRefreshAt = ctx.clock().instant().plusSeconds(cfg.feeRefreshSeconds());

        // Стартовать с неизвестным бюджетом хуже, чем не стартовать: ошибку подхватит
        // BotRuntimeService.start и переведёт бота в ERROR, сохранив желаемое состояние.
        //
        // Ссылка на метод, а не вызов: при выводе прибыли (режим по умолчанию)
        // реализованный P/L в бюджете не участвует, и загружать ради него всю
        // денежную книгу на старте незачем.
        this.activeBudget = availableBudget();

        // Отказ стартовать — осознанное решение: сетка, не окупающая комиссию,
        // будет исправно терять деньги на каждом обороте.
        this.sizing = GridValidator.validate(cfg, activeRange, ladder, constraints.minPriceIncrement(),
                fees, constraints.quantityStep(), ctx.execution().maxCapital(), activeBudget, ctx.carryDailyRate()).sizing();

        if (resolution.persist() || restoredStateCleared) {
            persistState();
        }
        updateSnapshot();
        // Поколение отмечается и при рестарте: для уже идущего оно ничего не меняет,
        // а для поднятого впервые — открывает отсчёт, без которого статистики не будет.
        rollGeneration();

        ctx.event(BotEventType.HOUSEKEEPING,
                "GRID готов: %d уровней от %s до %s (%s), шаг %s, %s, комиссия %s%%"
                        .formatted(activeRange.levels(),
                                activeRange.lower().toPlainString(),
                                activeRange.upper().toPlainString(),
                                activeRange.origin(),
                                ladder.effectiveStep().toPlainString(),
                                sizingSummary(),
                                fees.makerRoundTripRate().multiply(BigDecimal.valueOf(100)).toPlainString()));

        sweepHistoricalDust();
        seedTradingStatus();
        seedLastPriceOnStart();
    }

    /**
     * Шортовая сетка допускается пока только в бумажном режиме.
     *
     * Геометрия направления готова и покрыта зеркальным тестом, риск-контроль получил
     * потолки взамен прежнего запрета, книга научилась знаковым партиям. Чего ещё нет —
     * проверенного на живом счёте поведения: как ведут себя маржинальные показатели
     * в течение дня, во что обходится перенос на самом деле, что делает брокер на
     * открытии после ночи. Бумажный прогон отвечает на всё это, ничем не рискуя.
     *
     * Отказ на старте, а не молчаливое понижение до лонга: бот, который просят вести
     * шорт, а он ведёт лонг, — это худший из возможных ответов. Он выглядит рабочим.
     */
    private void requireShortIsAllowed() {
        if (direction != GridDirection.SHORT) {
            return;
        }
        if (!ctx.execution().marginEnabled()) {
            throw new IllegalStateException(
                    "Шортовая сетка требует маржинального режима: включите его у бота "
                            + "и разрешите маржу на подключении.");
        }
        if (!ctx.execution().dryRun() && !ctx.execution().allowLiveMargin()) {
            throw new IllegalStateException(
                    "Шортовая сетка живьём требует явного разрешения (allowLiveMargin). "
                            + "Она ни разу не работала на настоящем рынке: тестами проверена "
                            + "арифметика, но не поведение брокера. Прогоните её на бумаге, "
                            + "а включая живьём — начните с потолков, которые не жаль потерять.");
        }
        if (!ctx.execution().shortEnabledByInstrument()) {
            throw new IllegalStateException(
                    "Брокер не разрешает короткую позицию по этому инструменту.");
        }
    }

    /**
     * Та же подстраховка, что и для торгового статуса, но для ЦЕНЫ.
     *
     * Стрим последней цены присылает событие только при СДЕЛКЕ. Боту, поднятому с
     * восстановленным диапазоном, цену взять неоткуда: оценщик ATR при рестарте не
     * работает (диапазон уже известен), а {@link #ensureOrders} без цены выходит сразу.
     * Пока по инструменту не пройдёт чужая сделка, бот стоит без единой заявки — на
     * неликвидной бумаге это часы, а на выходных бесконечно.
     *
     * Ровно так 07.08.2026 на боевом сервере вели себя MAGN и RNFT: стартовали без
     * ошибок, писали «GRID готов», и не выставляли ничего. Свежесозданный MVID на том
     * же подключении торговал — потому что диапазон ему считали впервые и цена
     * приезжала REST-запросом заодно с ATR.
     *
     * Отказ здесь не фатален: цена — не условие корректности, а лишь повод действовать
     * раньше. Не получилось — дождёмся стрима или следующего тика.
     */
    private void seedLastPriceOnStart() {
        if (lastPrice != null) {
            return;
        }
        try {
            LastPrice price = ctx.exchange().marketData().getLastPrice(ctx.execution().instrumentId());
            if (price != null && price.price() != null && price.price().value() != null) {
                lastPrice = price.price().value();
                ctx.observedPrice(lastPrice, ctx.clock().instant());
            }
        } catch (Exception e) {
            log.debug("Не удалось получить стартовую цену для бота {}: {}", ctx.botId(), e.getMessage());
        }
    }

    /**
     * Подстраховка на случай запуска посреди сессии.
     *
     * Стрим статусов присылает событие только при СМЕНЕ состояния, поэтому бот,
     * поднятый посреди открытой сессии, без этого запроса просидел бы без единой
     * заявки до следующего перехода статуса, то есть часами.
     *
     * Спрашиваем статус САМОГО ИНСТРУМЕНТА, а не календарь площадок. Раньше здесь был
     * календарь, и он врал: запрос шёл без указания биржи, а признак «торги идут»
     * выставлялся, если открыта ХОТЬ ОДНА площадка из ответа. Ночью, когда нужная
     * биржа закрыта, а какая-то другая работает, бот считал сессию открытой и до утра
     * долбился в биржу заявками, ловя отказы на каждом тике.
     *
     * Не блокирует старт при ошибке: статус всё равно подтвердится стримом при
     * ближайшей смене состояния.
     */
    private void seedTradingStatus() {
        try {
            TradingStatusEvent status = ctx.exchange().marketData()
                    .getTradingStatus(ctx.execution().instrumentId());
            if (status == null) {
                return;
            }

            limitOrdersAvailable = status.limitOrdersAvailable();
            ctx.event(BotEventType.HOUSEKEEPING, limitOrdersAvailable
                    ? "Биржа принимает лимитные заявки (%s) — сетка расставится по первой цене"
                            .formatted(status.rawStatus())
                    : "Биржа сейчас не принимает лимитные заявки (%s) — жду открытия торгов"
                            .formatted(status.rawStatus()));

        } catch (Exception e) {
            log.warn("Не удалось получить торговый статус инструмента при старте: {}", e.getMessage());
        }
    }

    /**
     * Пока биржа закрыта — переспрашиваем её статус на каждом тике.
     *
     * Иначе бот, поднятый ночью, зависит от единственного события стрима об открытии
     * сессии. Стрим события теряет (на этом построена вся логика реконнекта здесь),
     * и потерянное открытие означало бы, что бот молчит весь торговый день — отказ
     * куда неприятнее лишнего запроса.
     *
     * Обратное направление — {@link #refreshTradingStatusAfterRejection()}.
     */
    private void refreshTradingStatusIfClosed() {
        if (limitOrdersAvailable) {
            return;
        }
        refreshTradingStatus(true);
    }

    /**
     * Заявку не приняли — спрашиваем биржу, не закрылась ли она.
     *
     * Раньше здесь ничего не спрашивали: считалось, что о закрытии сессии сообщит
     * стрим, а отклонённая заявка — худший случай, который сам себя исчерпает.
     * 08.08.2026 в 20:50 стрим сообщил о закрытии двум ботам из трёх, а третий
     * (MAGN) события не увидел — и до утра ставил заявку каждую минуту, получая
     * «30079 Instrument is not available for trading». Каждая попытка оставляла
     * запись PENDING, которую следом разбирала сверка: сотни строк в журнале
     * и ровно ноль пользы.
     *
     * Спрашиваем именно биржу, а не разбираем код ошибки: коды у каждой площадки
     * свои, а торговый статус — общий для всех и уже есть в модели. Один запрос
     * на неудачный проход, и то лишь пока бот считает торги открытыми: как только
     * статус переключится, {@link #ensureOrders} перестанет доходить до постановки.
     */
    private void refreshTradingStatusAfterRejection() {
        if (!limitOrdersAvailable) {
            return;
        }
        refreshTradingStatus(false);
    }

    /**
     * @param expectingOpen какого ответа ждём: true — «открылись ли», false — «закрылись ли».
     *                      Ответ в другую сторону здесь ничего не меняет: его уже
     *                      отражает текущий флаг.
     */
    private void refreshTradingStatus(boolean expectingOpen) {
        try {
            TradingStatusEvent status = ctx.exchange().marketData()
                    .getTradingStatus(ctx.execution().instrumentId());
            if (status != null && status.limitOrdersAvailable() == expectingOpen) {
                // Через общий обработчик: он сам напишет событие и расставит сетку.
                onTradingStatus(status);
            }
        } catch (Exception e) {
            log.debug("Не удалось переспросить торговый статус: {}", e.getMessage());
        }
    }

    @Override
    public void onStop() {
        // Флаг вместо обнуления полей — намеренно.
        //
        // Раньше здесь зануляли ctx, ladder и activeRange. Если бы событие всё же
        // дошло сюда одновременно с остановкой (close() ждёт рабочий поток не
        // бесконечно, а пять секунд), проверка готовности успевала бы пройти,
        // а следом падало разыменование уже отобранного поля.
        //
        // Флага достаточно: сама стратегия живёт ровно столько же, сколько хендлер,
        // так что освобождать здесь нечего.
        this.stopped = true;
        this.snapshot = null;   // только читается наружу, разыменований нет
    }

    private RangeResolution resolveActiveRange(ReconcileResult initialState, TradingConstraints constraints) {
        if (!cfg.autoRange()) {
            // Диапазон ручной сетки задаёт конфиг — его восстанавливать неоткуда и незачем.
            // А вот остальные флаги состояния принадлежат БОТУ, а не диапазону: ожидание
            // перестановки, идущая ликвидация, запрошенная плановая остановка. Пока
            // состояние здесь не читалось вовсе, поднятый супервизором ручной бот забывал
            // решение владельца и как ни в чём не бывало начинал покупать заново.
            GridStrategyState restored = ctx.loadState(GridStrategyState.class).orElse(null);
            return new RangeResolution(GridRange.manual(cfg, ctx.clock().instant()),
                    0, null, false, restored);
        }

        Optional<GridStrategyState> restored = ctx.loadState(GridStrategyState.class);
        if (restored.isPresent() && restored.get().activeRange() != null) {
            GridStrategyState state = restored.get();
            return new RangeResolution(state.activeRange(), Math.max(1, state.generation()), null, false, state);
        }

        BigDecimal position = initialState == null ? null : initialState.position();
        if (position == null) {
            throw new IllegalStateException(
                    "Автодиапазон нельзя создать без стартовой сверки позиции с биржей");
        }
        // Позиция ЖУРНАЛА, а не остаток счёта. Остаток может содержать чужое — монеты
        // соседнего бота, ручную покупку, пыль от прошлой жизни, — и запрет стартовать
        // из-за него означал бы, что новый бот с пустым журналом считает чужое своим.
        if (position.signum() != 0) {
            throw new IllegalStateException(
                    ("У бота есть позиция %s лот(ов), но сохранённый диапазон отсутствует. "
                            + "Автоматически пересчитывать уровни опасно: встречные продажи потеряют цены.")
                            .formatted(position.toPlainString()));
        }

        VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimate(
                ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                constraints.minPriceIncrement(), ctx.clock().instant());
        ctx.event(BotEventType.HOUSEKEEPING,
                "ATR %s по %d свечам: %s; стартовый диапазон %s..%s"
                        .formatted(cfg.atrInterval(), estimate.atr().candlesUsed(),
                                estimate.atr().value().toPlainString(),
                                estimate.range().lower().toPlainString(),
                                estimate.range().upper().toPlainString()));
        return new RangeResolution(estimate.range(), 1, estimate.referencePrice(), true, null);
    }

    // ==============================
    // EVENTS
    // ==============================

    /**
     * Главный триггер жизненного цикла. У T-Invest нет GTC: вчерашние заявки мертвы,
     * поэтому открытие торгов означает «сверься и расставь сетку заново».
     */
    @Override
    public void onTradingStatus(TradingStatusEvent event) {
        if (!isReady()) {
            return;
        }

        boolean was = limitOrdersAvailable;
        limitOrdersAvailable = event.limitOrdersAvailable();

        if (!was && limitOrdersAvailable) {
            // Новая сессия — новый коридор цен: прошлые отказы к ней отношения не имеют.
            placementCooldowns.clear();
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Торги открыты (%s) — сверяюсь и расставляю сетку".formatted(event.rawStatus()));
            ReconcileResult reconciled = reconcileAndCheck();
            if (awaitingDownwardReplacement) {
                manageDownwardLiquidation();
            } else {
                ensureOrders(reconciled);
            }
        } else if (was && !limitOrdersAvailable) {
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Торги закрыты (%s) — заявки истекут в конце сессии".formatted(event.rawStatus()));
        }
    }

    /**
     * Сердце сетки: исполнение порождает встречную заявку.
     * Купили на уровне i — продаём на i+1. Продали на j — уровень j-1 снова свободен
     * и будет откуплен на следующем проходе.
     */
    @Override
    public void onOrderUpdate(BotOrderView order) {
        if (!isReady() || order == null) {
            return;
        }
        if (awaitingDownwardReplacement) {
            reconcileAndCheck();
            manageDownwardLiquidation();
            return;
        }
        if (order.gridLevel() == null) {
            return;
        }
        // Реагируем на завершённое исполнение: частичное догонится следующим
        // событием по тому же clientOrderId.
        if (order.status() != OrderStatus.FILLED) {
            return;
        }

        if (order.side() == direction.openSide()) {
            placeCounterClose(order);
        } else {
            // Цикл закрыт — прибыль зафиксирована. Уровень ниже освободился под новую покупку.
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Цикл закрыт на уровне %d, уровень %d свободен"
                            .formatted(order.gridLevel(), order.gridLevel() - 1));
            if (awaitingUpperReplacement) {
                tryCompleteUpperReplacement();
            } else {
                ensureOrders(null);
            }
        }
    }

    /**
     * Встречная заявка сразу после открытия уровня — именно здесь возникает прибыль сетки.
     *
     * Лонг закрывает уровнем выше, шорт — уровнем ниже; в обоих случаях разница и есть
     * заработок за вычетом комиссии. grid_level встречной заявки — это ОТКРЫТЫЙ уровень,
     * который она закрывает, а не тот, по чьей цене выставлена. Единое правило для всех
     * веток: иначе уровень не считается занятым и выкупается снова.
     */
    private void placeCounterClose(BotOrderView filledOpen) {
        int openLevel = filledOpen.gridLevel();
        BigDecimal price = ladder.priceAt(direction.closeLevelOf(openLevel));

        if (price == null) {
            // Открылись на крайнем уровне лесенки — закрывать дальше некуда.
            ctx.warn("Открытие на крайнем уровне сетки (%d): встречной заявки нет".formatted(openLevel));
            return;
        }

        // Сколько этого уровня уже выставлено на закрытие. Считаем именно количество:
        // проверка «есть ли хоть одна заявка» не давала повторно закрыть уровень,
        // на котором лежит несколько заявок, а её отсутствие — наоборот, плодило дубли.
        BigDecimal covered = ctx.gateway().openOrders(ctx.botId()).stream()
                .filter(o -> o.side() == direction.closeSide()
                        && o.gridLevel() != null && o.gridLevel() == openLevel)
                .map(BotOrderView::remainingQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal held = computeHeldQuantityByLevel()
                .getOrDefault(openLevel, BigDecimal.ZERO);
        BigDecimal closable = sellableQuantity(held.subtract(covered), price);
        if (closable.signum() <= 0) {
            return;
        }

        place(direction.closeSide(), price, openLevel, closable);
    }

    /**
     * Сколько с уровня действительно можно выставить на продажу.
     *
     * Продаём то, чем ВЛАДЕЕМ, а не то, что планировали купить. Разница не косметическая:
     * биржи, удерживающие комиссию из получаемой валюты (Poloniex берёт её монетой),
     * зачисляют по покупке строго меньше заявленного, и запланированный размер уровня
     * недостижим в принципе. Прежнее условие «продавать, только когда куплено не меньше
     * плана» на таких биржах не выполнялось НИКОГДА: заявка на продажу либо не ставилась
     * вовсе, либо уходила на бирже необеспеченной и отбивалась как 21721.
     *
     * Остаток меньше шага количества или дешевле минимальной суммы заявки биржа не примет.
     * Такую пыль возвращаем нулём молча: ставить заведомо отвергаемую заявку каждый проход
     * означало бы поток ложных отказов риск-контроля вместо торговли.
     */
    private BigDecimal sellableQuantity(BigDecimal available, BigDecimal price) {
        if (available == null || available.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal quantity = ctx.execution().quantizeDown(available);
        if (quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal minNotional = ctx.execution().minNotional();
        if (minNotional != null && price != null && quantity.multiply(price).compareTo(minNotional) < 0) {
            return BigDecimal.ZERO;
        }
        return quantity;
    }

    /**
     * Разовый ремонт на старте: пыль копилась с первого дня, а учёт её появился позже.
     *
     * Осевшие хвосты никуда не делись — они лежат недоеденными остатками партий в
     * книге, вместе со своей себестоимостью. Проход переводит их в корзину задним
     * числом; он идемпотентен, поэтому повторный запуск бота ничего не удваивает.
     *
     * Сбой здесь не повод не стартовать: пыль подождёт следующего запуска, а до тех
     * пор её соберёт обычный проход по мере закрытия циклов.
     */
    private void sweepHistoricalDust() {
        try {
            BigDecimal swept = ctx.sweepUntradableRemainders();
            if (swept != null && swept.signum() > 0) {
                ctx.event(BotEventType.HOUSEKEEPING,
                        "Собрана ранее осевшая пыль: %s".formatted(plainQuantity(swept)));
            }
        } catch (Exception e) {
            ctx.error("Не удалось собрать ранее осевшую пыль", e);
        }
    }

    /**
     * Собирает по закрытым уровням то, что уже невозможно продать, в корзину пыли.
     *
     * Условий два, и оба обязательны. Уровень должен быть СВОБОДЕН — ни покупки,
     * ни продажи на нём не висит, значит цикл закрыт и остаток уже ничей. И остаток
     * должен быть непродаваем по отдельности: мельче шага количества либо дешевле
     * минимальной суммы заявки. Без первого условия в пыль ушла бы половина живого
     * цикла, без второго — деньги, которые можно продать прямо сейчас.
     *
     * Себестоимость каждого хвоста считает книга: она знает, из какой партии он остался.
     */
    private void sweepDust(Map<Integer, BotOrderView> openBuys,
                           Map<Integer, BigDecimal> openSellQuantityByLevel) {
        Map<Integer, BigDecimal> heldByLevel = heldIncludingUncollectedDust();
        if (heldByLevel.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, BigDecimal> entry : heldByLevel.entrySet()) {
            int level = entry.getKey();
            if (openBuys.containsKey(level)
                    || openSellQuantityByLevel.getOrDefault(level, BigDecimal.ZERO).signum() > 0) {
                continue;
            }
            BigDecimal held = entry.getValue();
            if (held == null || held.signum() <= 0
                    || sellableQuantity(held, ladder.priceAt(level)).signum() > 0) {
                continue;
            }
            try {
                ctx.recordDust(level, held);
                ctx.event(BotEventType.HOUSEKEEPING,
                        "Уровень %d закрыт, непродаваемый хвост %s ушёл в пыль"
                                .formatted(level, plainQuantity(held)));
            } catch (Exception e) {
                ctx.error("Не удалось записать пыль уровня " + level, e);
                return;
            }
        }
    }

    /**
     * Держит одну заявку на продажу накопленной пыли.
     *
     * Цена — себестоимость корзины плюс комиссия оборота плюс небольшая наценка:
     * пыль продаётся не ради заработка, а чтобы перестать быть пылью, но продавать
     * её в убыток бессмысленно — она никуда не денется и подождёт.
     *
     * Долив: если пыль прибыла, а заявка ещё висит, старая снимается и выставляется
     * новая на всё сразу. Себестоимость при этом пересчитывается — у нового хвоста
     * своя цена, и держать старую значило бы продать корзину дешевле, чем она обошлась.
     */
    private void manageDustSale() {
        if (lastPrice == null || !limitOrdersAvailable || halted || positionMismatched) {
            return;
        }
        BotOrderView open = openDustOrder();
        DustBucket bucket;
        try {
            bucket = ctx.dust();
        } catch (Exception e) {
            ctx.error("Не удалось прочитать накопленную пыль", e);
            return;
        }
        if (bucket == null || bucket.isEmpty()) {
            return;
        }

        BigDecimal quantity = sellableQuantity(bucket.quantity(), dustPrice(bucket));
        if (quantity.signum() <= 0) {
            // Накопленного всё ещё не хватает на заявку, которую биржа примет.
            return;
        }

        BigDecimal price = dustPrice(bucket);
        if (open != null) {
            boolean sameQuantity = open.remainingQuantity().compareTo(quantity) == 0;
            boolean samePrice = open.limitPrice() != null
                    && open.limitPrice().compareTo(price) == 0;
            if (sameQuantity && samePrice) {
                return;
            }
            try {
                ctx.gateway().cancel(ctx.execution(), open.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять заявку на продажу пыли перед переоценкой", e);
                return;
            }
        }

        try {
            // Пыль всегда закрывает позицию — своей она не бывает по определению.
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    OrderSide.SELL, quantity, price, null, OrderPurpose.DUST, GridRole.CLOSE));
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Продажа пыли: %s по %s (себестоимость %s)"
                            .formatted(plainQuantity(quantity), price.toPlainString(),
                                    plainQuantity(bucket.averagePrice())));
        } catch (RiskRejectedException e) {
            ctx.event(BotEventType.RISK_BLOCKED, "Продажа пыли пока запрещена лимитом: " + e.getMessage());
        } catch (Exception e) {
            ctx.error("Не удалось выставить заявку на продажу пыли", e);
        }
    }

    /**
     * Цена продажи пыли: себестоимость плюс комиссия оборота плюс наценка.
     *
     * Ниже себестоимости пыль не отдаём — она не портится. Выше рынка тоже ничего
     * страшного: это обычная лимитная заявка, она просто подождёт своей цены, а если
     * расчётная цена окажется ниже рынка, биржа исполнит её по рыночной, то есть лучше.
     */
    private BigDecimal dustPrice(DustBucket bucket) {
        BigDecimal average = bucket.averagePrice();
        BigDecimal fees = activeFees == null ? BigDecimal.ZERO : activeFees.makerRoundTripRate();
        BigDecimal price = average.multiply(BigDecimal.ONE.add(fees).add(DUST_SALE_MARGIN));
        BigDecimal step = ctx.constraints().minPriceIncrement();
        if (step == null || step.signum() <= 0) {
            return price;
        }
        // Округляем ВВЕРХ: округление вниз съело бы ту самую наценку, ради которой всё.
        return price.divide(step, 0, RoundingMode.CEILING).multiply(step);
    }

    /**
     * Пауза после отказа биржи: столько уровень не пытаемся выставить снова.
     *
     * Минута — период тика, и повтор на каждом тике как раз и давал шторм в журнале.
     * Пятнадцать минут — компромисс: достаточно редко, чтобы не шуметь, достаточно
     * часто, чтобы вернуться в игру, как только коридор цен раздвинется.
     */
    private static final Duration PLACEMENT_COOLDOWN = Duration.ofMinutes(15);

    private static String placementKey(OrderSide side, int level) {
        return side + ":" + level;
    }

    private boolean levelIsCoolingDown(OrderSide side, int level) {
        Instant until = placementCooldowns.get(placementKey(side, level));
        return until != null && ctx.clock().instant().isBefore(until);
    }

    private void clearPlacementFailure(OrderSide side, int level) {
        placementCooldowns.remove(placementKey(side, level));
    }

    /**
     * Сообщаем об отказе ОДИН раз за паузу, а не каждый проход: журнал событий
     * читают люди, и сотня одинаковых строк прячет всё остальное.
     */
    private void notePlacementFailure(OrderSide side, int level, Exception e) {
        String key = placementKey(side, level);
        boolean firstTime = placementCooldowns.put(key, ctx.clock().instant().plus(PLACEMENT_COOLDOWN)) == null;
        if (firstTime) {
            ctx.error(("Не удалось выставить заявку на уровне %d (%s). "
                    + "Повторю не раньше чем через %d минут")
                    .formatted(level, side, PLACEMENT_COOLDOWN.toMinutes()), e);
        } else {
            log.debug("Уровень {} снова отвергнут биржей: {}", level, e.getMessage());
        }
    }

    /**
     * Роль заявки: своя, если известна, иначе выведенная из стороны и направления.
     *
     * Запасной путь нужен не для красоты. Роль появилась позже самих заявок, и в
     * журнале остаются строки, записанные до неё; ровно так же её не заполняют те,
     * кто собирает представление заявки в обход журнала. Молча считать такую заявку
     * закрывающей нельзя — её бы перестали снимать перед перестановкой диапазона.
     */
    private GridRole roleOf(BotOrderView order) {
        return order.gridRole() != null ? order.gridRole() : direction.roleOf(order.side());
    }

    /** Наша живая заявка на продажу пыли, если она есть. */
    private BotOrderView openDustOrder() {
        return ctx.gateway().openOrders(ctx.botId()).stream()
                .filter(o -> o.purpose() == OrderPurpose.DUST)
                .findFirst()
                .orElse(null);
    }

    /** Заявки, которые считаются «нашими» для проверок сетки. Пыль живёт отдельно. */
    private List<BotOrderView> gridOrders() {
        return ctx.gateway().openOrders(ctx.botId()).stream()
                .filter(o -> o.purpose() != OrderPurpose.DUST)
                .toList();
    }

    /**
     * Позиция закрыта настолько, насколько её вообще возможно закрыть на бирже.
     *
     * Критерий один и тот же для всего кода: остаток «закрыт», если из него нельзя
     * составить заявку, которую биржа примет, — то есть ровно {@link #sellableQuantity}.
     * Оба его условия здесь по делу:
     * <ul>
     *   <li>мельче шага количества — остаток нечем выразить. Такой хвост оставляет
     *       КАЖДЫЙ закрытый цикл там, где комиссия удерживается монетой: зачисляется
     *       0.09101534, продать можно 0.091015;</li>
     *   <li>дешевле минимальной суммы заявки — заявку отвергнет биржа. Именно сюда
     *       попадает накопленное: хвосты десяти циклов складываются в остаток,
     *       который шагу уже кратен, а по деньгам всё ещё ничто.</li>
     * </ul>
     *
     * Ждать такой остаток бессмысленно вдвойне: продать его нельзя сейчас и нельзя
     * будет потом. Уровневый учёт следующего поколения его не увидит — {@link
     * #computeHeldQuantityByLevel()} отбирает заявки по времени начала диапазона, —
     * так что он не сольётся с будущей покупкой и не станет продаваемым сам собой.
     *
     * Требовать здесь строгого нуля значило требовать невыполнимого. 08.08.2026 бот на
     * SOL/USDT подтвердил пробой вверх, снял покупки, дождался исполнения всех продаж —
     * и встал навсегда из-за 0.0000031 монеты на четверть тысячной доллара: заявок нет,
     * исполняться нечему, событий не пишется. Снаружи это выглядело как «бот молчит».
     */
    private boolean positionIsFlat(BigDecimal position) {
        if (position == null) {
            return false;
        }
        // По МОДУЛЮ: для шортовой сетки отрицательная позиция — это норма, а не авария,
        // и «закрыта» она ровно тогда же, когда лонговая, — когда из остатка не составить
        // заявки, которую примет биржа. Проверку знака, которая раньше стояла здесь,
        // выполняет отдельная сверка направления: она отличает ожидаемый шорт от того,
        // которого быть не должно.
        BigDecimal exposure = position.abs();
        // Пыль в позиции журнала есть, но сетке она уже не принадлежит: её продаёт
        // отдельная заявка, живущая своей жизнью. Ждать её здесь значило бы ждать
        // ровно того, чего эта проверка и не должна ждать.
        BigDecimal tradable = exposure.subtract(dustQuantity());
        if (tradable.signum() <= 0) {
            return true;
        }
        return sellableQuantity(tradable, lastPrice).signum() <= 0;
    }

    /** Сколько из позиции журнала — накопленная пыль. Ошибка чтения = «пыли нет». */
    private BigDecimal dustQuantity() {
        try {
            DustBucket bucket = ctx.dust();
            return bucket == null ? BigDecimal.ZERO : bucket.quantity();
        } catch (Exception e) {
            log.debug("Не удалось прочитать корзину пыли: {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public void onPrice(LastPrice price) {
        if (!isReady() || price == null || price.price() == null) {
            return;
        }
        lastPrice = price.price().value();

        // Плечо ведём первым: непокрытая позиция не ждёт своей очереди.
        manageHedgeLeg();

        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (processUpperBreakout()) {
            return;
        }
        if (processLowerBreakout()) {
            return;
        }
        if (checkRangeExit()) {
            return;
        }
        ensureOrders(null);
    }

    /**
     * После разрыва стрима состояние могло уйти вперёд без нас.
     * Сверку уже сделал хендлер — здесь только пересобираем заявки.
     */
    @Override
    public void onStreamReconnect() {
        if (!isReady()) {
            return;
        }
        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (awaitingUpperReplacement && tryCompleteUpperReplacement()) {
            return;
        }
        ensureOrders(null);
    }

    @Override
    public void onTick() {
        if (!isReady()) {
            return;
        }
        // Сторож: периодическая сверка на случай, если стрим молчит не потому,
        // что рынок спокоен, а потому что мы ослепли.
        ReconcileResult reconciled = reconcileAndCheck();

        // Тот же сторож для торговой сессии: потерянное стримом открытие иначе
        // означало бы, что бот молчит весь день.
        refreshTradingStatusIfClosed();

        checkMarginHealth();
        manageHedgeLeg();

        // Отдельно от ensureOrders: та выходит раньше на закрытой бирже и при
        // расхождении позиции, а плановая остановка вполне может завершиться и там —
        // например, если продавать было нечего с самого начала.
        completeScheduledStopIfDone();

        if (awaitingDownwardReplacement) {
            manageDownwardLiquidation();
            return;
        }
        if (lastPrice != null && processUpperBreakout()) {
            return;
        }
        if (lastPrice != null && processLowerBreakout()) {
            return;
        }
        if (lastPrice != null && checkRangeExit()) {
            return;
        }
        ensureOrders(reconciled);
    }

    /**
     * Сторож обеспечения: сообщает, когда счёт подходит к краю.
     *
     * На этой фазе он ТОЛЬКО пишет события и не трогает ни одной заявки. Так и
     * задумано: прежде чем позволить сторожу закрывать позицию, надо увидеть на
     * живом счёте, какие числа брокер присылает на самом деле и как они себя ведут
     * в течение дня. Сторож, который начал бы закрывать позиции по непроверенному
     * порогу, опаснее того, чего он должен не допустить.
     *
     * Смотрит и лонговый бот тоже. Обеспечение — свойство СЧЁТА, а не бота: если
     * его выбрал сосед по счёту, отвечать за это будут все, включая тех, кто плечом
     * не пользуется.
     *
     * Молчит, когда показателей нет. Пустой ответ означает «не знаем» — ни площадка
     * не умеет отвечать, ни брокер не ответил, — и тревожиться на нём значило бы
     * приучить к ложным срабатываниям ровно тот сигнал, который обязан быть редким.
     */
    private void checkMarginHealth() {
        Optional<MarginAttributes> current;
        try {
            current = ctx.marginAttributes();
        } catch (Exception e) {
            log.debug("Не удалось получить обеспечение счёта: {}", e.getMessage());
            return;
        }
        // null наравне с пустым: контекст — внешняя для стратегии вещь, и падать
        // на сторожевом тике из-за того, что кто-то вернул null вместо Optional,
        // значило бы уронить бота ровно тем средством, которое его бережёт.
        if (current == null || current.isEmpty()) {
            return;
        }
        MarginAttributes margin = current.get();

        boolean call = margin.marginCall();
        boolean near = margin.nearStopOut(MARGIN_WARNING_LEVEL);
        if (!call && !near) {
            // Отпустило — следующее ухудшение снова будет сказано вслух.
            marginWarningReported = false;
            return;
        }
        // Раз за эпизод: тик частый, а журнал событий читают люди.
        if (marginWarningReported) {
            return;
        }
        marginWarningReported = true;

        ctx.event(call ? BotEventLevel.ERROR : BotEventLevel.WARN, BotEventType.RISK_BLOCKED,
                (call
                        ? "Маржин-колл: обеспечения счёта уже не хватает (достаточность %s, не хватает %s). "
                        : "Обеспечение счёта на пределе: достаточность %s при пороге %s. ")
                        .formatted(
                                plainQuantity(margin.fundsSufficiencyLevel()),
                                call ? plain(margin.amountOfMissingFunds())
                                        : plainQuantity(MARGIN_WARNING_LEVEL))
                        + "Ликвидный портфель %s, начальная маржа %s, минимальная %s. "
                        .formatted(plain(margin.liquidPortfolio()), plain(margin.startingMargin()),
                                plain(margin.minimalMargin()))
                        + "Показатели общие для всего счёта. Бот их только наблюдает "
                        + "и позицию из-за них не закрывает.");
    }

    /**
     * Сверка с биржей и контроль расхождения позиции.
     *
     * Расхождение означает, что мы не знаем своей реальной позиции. Торговать в таком
     * состоянии нельзя: именно это позволило ошибке пересчёта лотов в штуки
     * превратиться в лишние заявки на продажу. Флаг снимается сам, как только
     * позиция сойдётся, — вмешательство не требуется.
     */
    private ReconcileResult reconcileAndCheck() {
        ReconcileResult reconciled = ctx.gateway().reconcile(ctx.execution());
        onReconcile(reconciled);
        return reconciled;
    }

    /**
     * Единая точка обработки итога сверки — чьей угодно.
     *
     * Раньше флаг расхождения выставлялся только здешней сверкой, а стартовую и
     * послереконнектную делал хендлер, и их результат сюда не доходил. Из-за этого
     * бот, поднятый с расхождением в 5 лотов, честно записал его в журнал событий
     * и тут же выставил продажи на позицию, которой на бирже не существовало.
     */
    @Override
    public void onReconcile(ReconcileResult reconciled) {
        if (reconciled == null || ctx == null) {
            return;
        }

        BigDecimal mismatch = reconciled.positionMismatch();
        // Только НЕДОСТАЧА. Излишек на счёте означает лишь, что там есть чужое:
        // монеты соседнего бота, ручная покупка или неторгуемая пыль от прошлой жизни.
        // Останавливаться из-за него значит требовать, чтобы бот был единственным
        // владельцем инструмента на счёте, — а тогда пылинка в 0.000348 запирает бота
        // навсегда, потому что продать её нельзя.
        boolean diverged = reconciled.positionShortfall();

        consecutiveMismatches = diverged ? consecutiveMismatches + 1 : 0;

        // Первое расхождение — почти всегда гонка расчётов у брокера, а не потеря сделки.
        // Останавливаем торговлю только с подтверждения второй сверкой.
        boolean mismatched = consecutiveMismatches >= MISMATCH_CONFIRMATIONS;

        if (mismatched && !positionMismatched) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    ("Позиция журнала расходится с биржей на %s лот(ов), расхождение подтверждено "
                            + "%d сверками подряд. Торговля приостановлена до его устранения — "
                            + "выставлять заявки, не зная своей позиции, опасно.")
                            .formatted(mismatch.toPlainString(), consecutiveMismatches));
        } else if (!mismatched && positionMismatched) {
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Позиция сошлась с биржей — торговля возобновляется");
        }

        positionMismatched = mismatched;
        reconciledPosition = reconciled.position();

        if (!positionMismatched && awaitingUpperReplacement) {
            tryCompleteUpperReplacement();
        }
        if (!positionMismatched && awaitingDownwardReplacement
                && reconciledPosition != null && reconciledPosition.signum() == 0) {
            tryCompleteDownwardReplacement();
        }
    }

    // ==============================
    // CORE
    // ==============================

    /**
     * Приводит выставленные заявки в соответствие с состоянием.
     *
     * Порядок намеренный: сначала продажи (высвобождают капитал и фиксируют прибыль),
     * потом покупки. Так лимит капитала не блокирует фиксацию уже заработанного.
     */
    private void ensureOrders(ReconcileResult reconciled) {
        if (!limitOrdersAvailable) {
            return;
        }
        if (lastPrice == null) {
            return;
        }
        if (positionMismatched) {
            // Не знаем свою позицию — не торгуем. Ровно отсутствие этой проверки
            // позволило ошибке пересчёта лотов превратиться в лишние заявки:
            // сверка честно сообщала о расхождении, а бот продолжал работать.
            return;
        }
        refreshFeesIfNeeded();

        List<BotOrderView> open = ctx.gateway().openOrders(ctx.botId());
        Map<Integer, BotOrderView> openBuys = new HashMap<>();

        // Продажи считаем В ЛОТАХ, а не «одна заявка на уровень». На уровне их может
        // висеть несколько, и Map<уровень, заявка> вторую молча затирала: покрытие
        // всегда выглядело как один лот, разница «куплено минус покрыто» не убывала,
        // и каждый проход добавлял ещё одну продажу — до упора в лимит частоты.
        Map<Integer, BigDecimal> openSellQuantityByLevel = new HashMap<>();
        int openOrderCount = 0;

        for (BotOrderView o : open) {
            if (o.gridLevel() == null) {
                continue;
            }
            openOrderCount++;
            if (roleOf(o) == GridRole.OPEN) {
                openBuys.put(o.gridLevel(), o);
            } else {
                openSellQuantityByLevel.merge(o.gridLevel(), o.remainingQuantity(), BigDecimal::add);
            }
        }

        // Сначала убираем с уровней то, что уже никогда не продать: иначе уровень
        // остаётся формально занятым непродаваемым хвостом, и следующая покупка
        // на нём не встанет. Порядок важен — heldByLevel после этого пересчитан.
        // Пыль — понятие лонговое: непродаваемый хвост КУПЛЕННОГО не портится и может
        // ждать своей цены. У шорта хвост ведёт себя наоборот — он не ждёт, а копит
        // плату за перенос, и «собрать его в корзину» значило бы спрятать растущее
        // обязательство. Поэтому в шортовой сетке сборщик молчит, а незакрываемый
        // остаток остаётся видимым и требует человека.
        if (direction == GridDirection.LONG) {
            sweepDust(openBuys, openSellQuantityByLevel);
        }
        Map<Integer, BigDecimal> heldByLevel = computeHeldQuantityByLevel();
        reportUncoveredPosition(heldByLevel);

        openOrderCount += placeMissingSells(openBuys, openSellQuantityByLevel, heldByLevel, openOrderCount);
        if (!buyingStopped) {
            // Порядок намеренный: сначала снять заявки прежнего размера, потом ставить.
            // Снятые тут же освобождают место под лимитом активных заявок, и уровень
            // не остаётся пустым до следующего прохода.
            if (resizeRequested) {
                openOrderCount -= resizeOpenBuys(openBuys);
            }
            placeMissingBuys(openBuys, openSellQuantityByLevel, heldByLevel, openOrderCount);
        }

        // Пыль продаётся последней и независимо от сетки: её заявка не занимает уровня
        // и не участвует в лимите капитала сетки — этот товар уже куплен и оплачен.
        if (direction == GridDirection.LONG) {
            manageDustSale();
        }

        // Продавать могло стать нечего именно сейчас — например, последняя встречная
        // продажа исполнилась на этом же проходе.
        completeScheduledStopIfDone();
    }

    /**
     * Сколько купленного и ещё не проданного лежит на каждом уровне.
     *
     * Считается по журналу: исполненные покупки уровня минус исполненные продажи,
     * этот уровень закрывающие. По одним открытым заявкам это не выводится —
     * исполненная покупка из них исчезает, и уровень выглядел бы свободным.
     *
     * Учитываются только заявки текущего поколения. Номер уровня осмыслен лишь внутри
     * своей лесенки: после перестановки диапазона уровень 7 — это уже другая цена.
     * Вдобавок принудительная ликвидация продаёт позицию целиком, одной заявкой без
     * уровня (её нечему сопоставить — она закрывает сразу несколько), и потому не
     * гасит ни один уровень. Вместе это давало сетке нового поколения уровни, которые
     * она считала занятыми несуществующей позицией: продажу на них запрещал
     * риск-контроль, а покупку — сама эта запись.
     */
    private Map<Integer, BigDecimal> computeHeldQuantityByLevel() {
        Map<Integer, BigDecimal> held = rawHeldByLevel();


        // Уже собранное в корзину пыли уровню больше не принадлежит. Вычитаем именно
        // здесь: заявки в журнале ордеров остаются навсегда, и без вычитания уровень
        // считался бы занятым тем, что с него давно изъято, — а изъятие бы повторялось
        // каждый проход, наращивая корзину из воздуха.
        Map<Integer, BigDecimal> collected = dustByLevel();
        collected.forEach((level, quantity) -> held.computeIfPresent(level,
                (__, value) -> value.subtract(quantity)));

        reportNegativeLevels(held);

        // Остаток мельче шага количества продать невозможно: биржа такую заявку не примет.
        // Считать уровень занятым из-за него — значит вывести уровень из игры навсегда:
        // продать нечего, а покупку блокирует сама эта запись. Такая пыль неизбежна там,
        // где комиссия удерживается монетой: зачисляется 140.544348, а продать можно
        // 140.544 — разница оседает на уровне после КАЖДОГО закрытого цикла.
        BigDecimal step = ctx.execution().quantityStep();
        held.values().removeIf(v -> v.signum() <= 0 || (step != null && v.compareTo(step) < 0));
        return held;
    }

    /**
     * Отрицательный остаток уровня — не то же самое, что пустой.
     *
     * Ноль означает «цикл закрыт», и молчать о нём правильно. Минус означает, что по
     * уровню продано больше, чем куплено, — то есть учёт уровня врёт, и следующая
     * покупка встанет на уровень, который на самом деле не свободен. Отбрасывались оба
     * случая одним {@code signum() <= 0}, и именно эта немота прятала обрезанную выборку
     * журнала: уровень уходил в минус, тихо исчезал, и позиция оставалась без продажи.
     *
     * Сообщаем один раз на уровень: тик частый, а журнал событий читают люди.
     */
    private void reportNegativeLevels(Map<Integer, BigDecimal> held) {
        held.forEach((level, quantity) -> {
            if (quantity.signum() >= 0) {
                negativeLevelsReported.remove(level);
                return;
            }
            if (negativeLevelsReported.add(level)) {
                ctx.warn(("Учёт уровня %d разошёлся: продано больше, чем куплено (остаток %s). "
                        + "Уровень исключён из расчёта до выяснения.")
                        .formatted(level, plainQuantity(quantity)));
            }
        });
    }

    /**
     * Купленное минус проданное по уровням текущего поколения, без каких-либо скидок.
     *
     * Источник — ВСЯ история поколения, а не последние N записей журнала. Обрезка
     * здесь стоила застрявшей позиции: продажа всегда новее своей покупки, поэтому на
     * границе окна уровень терял покупку раньше закрывшей её продажи и уходил в ноль
     * или в минус. 10.08.2026 на боте DOGE уровень с реальными +20 лотами показывал в
     * окне ровно 0, исчезал из учёта, трижды перекупался и не получил ни одной
     * встречной продажи — а перестановка диапазона вверх ждала закрытия позиции,
     * которой больше никто не видел.
     */
    private Map<Integer, BigDecimal> rawHeldByLevel() {
        Instant generationStart = activeRange == null ? Instant.EPOCH : activeRange.since();
        Map<Integer, BigDecimal> held = new HashMap<>();
        for (BotOrderView o : ctx.gateway().levelOrders(ctx.botId(), generationStart)) {
            BigDecimal executed = o.executedQuantity();
            if (o.gridLevel() == null || executed == null || executed.signum() <= 0) {
                continue;
            }
            // По РОЛИ, а не по стороне: в шорте позицию набирает продажа, и «покупка
            // значит открытие» перестаёт быть правдой. Остаток уровня при этом
            // остаётся положительным в обоих направлениях — это модуль незакрытой
            // экспозиции, а не позиция со знаком.
            BigDecimal delta = roleOf(o) == GridRole.OPEN ? executed : executed.negate();
            held.merge(o.gridLevel(), delta, BigDecimal::add);
        }
        return held;
    }

    /**
     * Что на уровнях лежит на самом деле — до отбрасывания непродаваемых хвостов.
     *
     * Нужна ровно сборщику пыли: {@link #computeHeldQuantityByLevel()} эти хвосты
     * прячет (и правильно делает — иначе уровень выпадает из игры), но спрятанное
     * невозможно собрать.
     */
    private Map<Integer, BigDecimal> heldIncludingUncollectedDust() {
        Map<Integer, BigDecimal> held = rawHeldByLevel();
        dustByLevel().forEach((level, quantity) -> held.computeIfPresent(level,
                (__, value) -> value.subtract(quantity)));
        held.values().removeIf(v -> v.signum() <= 0);
        return held;
    }

    /**
     * Позиция, за которую не отвечает ни один уровень, — это позиция без выхода.
     *
     * Продажи ставятся только по уровневому учёту, а ждут закрытия позиции (плановая
     * остановка, перестановка вверх) — по позиции ЖУРНАЛА, которая считается по всей
     * истории бота. Пока эти две величины сходятся, разницы между ними не видно; как
     * только расходятся — на разницу никто никогда не выставит заявку, и ожидание
     * становится вечным. Раньше расхождение не проверялось вовсе, и обнаруживалось
     * оно в худший момент: при пробое диапазона, спустя сутки после появления.
     *
     * Допуск — тот же, что и везде: непродаваемый остаток расхождением не считается.
     * Пыль вычитается по той же причине, по которой её вычитает {@link #positionIsFlat}:
     * она принадлежит отдельной заявке, а не сетке.
     */
    private void reportUncoveredPosition(Map<Integer, BigDecimal> heldByLevel) {
        if (reconciledPosition == null || positionMismatched || lastPrice == null) {
            return;
        }
        BigDecimal uncovered = uncoveredPosition(heldByLevel);

        if (sellableQuantity(uncovered, lastPrice).signum() <= 0) {
            uncoveredPositionReported = false;
            return;
        }
        if (uncoveredPositionReported) {
            return;
        }
        uncoveredPositionReported = true;
        ctx.event(BotEventType.RISK_BLOCKED,
                ("Позиция %s не покрыта уровнями сетки: за %s не отвечает ни один уровень, "
                        + "и встречную продажу на них бот не выставит. Проверьте уровни поколения %d.")
                        .formatted(plainQuantity(reconciledPosition), plainQuantity(uncovered),
                                gridGeneration));
    }

    /**
     * Часть позиции журнала, за которую не отвечает ни один уровень и ни корзина пыли.
     * Именно её никто никогда не выставит на продажу.
     */
    private BigDecimal uncoveredPosition(Map<Integer, BigDecimal> heldByLevel) {
        BigDecimal covered = heldByLevel.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(dustQuantity());
        return reconciledPosition.subtract(covered);
    }

    /** Сколько с каждого уровня уже переведено в пыль. Ошибка чтения = «нисколько». */
    private Map<Integer, BigDecimal> dustByLevel() {
        try {
            Map<Integer, BigDecimal> collected = ctx.dustByLevel();
            return collected == null ? new HashMap<>() : new HashMap<>(collected);
        } catch (Exception e) {
            log.debug("Не удалось прочитать пыль по уровням: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Уровни, которые нельзя занимать новой покупкой.
     *
     * Занятость складывается из трёх независимых признаков, и пропуск любого из них
     * означает повторную закупку уровня, цикл которого ещё не закрыт:
     * <ul>
     *   <li>на уровне уже висит наша покупка;</li>
     *   <li>на уровне лежат купленные и не проданные лоты;</li>
     *   <li>эти лоты уже выставлены на продажу — заявка ждёт исполнения.</li>
     * </ul>
     *
     * Третий признак существеннее, чем кажется. Купленное на уровне и выставленное
     * на продажу перестаёт быть «свободным» ровно до момента, когда продажа исполнится:
     * до тех пор уровень занят, сколько бы раз цена ни возвращалась к цене покупки.
     * Иначе откат к цене покупки докупает тот же уровень, купленное подмешивается
     * к незакрытому циклу, и вместо одной встречной продажи на уровне копится стопка.
     */
    private boolean levelIsOccupied(int level,
                                    Map<Integer, BotOrderView> openBuys,
                                    Map<Integer, BigDecimal> openSellQuantityByLevel,
                                    Map<Integer, BigDecimal> heldByLevel) {
        return openBuys.containsKey(level)
                || heldByLevel.containsKey(level)
                || openSellQuantityByLevel.containsKey(level);
    }

    /**
     * Купленное, но не выставленное на продажу.
     *
     * Продажа привязана к уровню покупки, который закрывает: её grid_level — это
     * уровень ПОКУПКИ, а цена берётся уровнем выше. Раньше эта ветка ставила продажи
     * по своему правилу («ближайший свободный уровень выше цены»), несовместимому
     * с правилом встречной продажи, — из-за чего уровень покупки не считался занятым
     * и выкупался снова и снова.
     */
    private int placeMissingSells(Map<Integer, BotOrderView> openBuys,
                                  Map<Integer, BigDecimal> openSellQuantityByLevel,
                                  Map<Integer, BigDecimal> heldByLevel,
                                  int openOrderCount) {
        int placed = 0;

        for (Map.Entry<Integer, BigDecimal> entry : heldByLevel.entrySet()) {
            int buyLevel = entry.getKey();
            BigDecimal held = entry.getValue();

            // Покупка уровня ещё работает — позиция на нём не окончательна. Продавать
            // исполненную часть сейчас значит разорвать цикл уровня надвое: на остаток
            // придётся вторая продажа, и вместо одной встречной заявки копится стопка.
            // Дождаться закрытия покупки дешевле: её доисполнение — вопрос минут.
            if (openBuys.containsKey(buyLevel)) {
                continue;
            }

            BigDecimal covered = openSellQuantityByLevel.getOrDefault(buyLevel, BigDecimal.ZERO);
            BigDecimal price = ladder.priceAt(direction.closeLevelOf(buyLevel));

            BigDecimal sellable = sellableQuantity(held.subtract(covered), price);
            if (sellable.signum() <= 0) {
                continue;
            }
            // Лимит активных заявок распространяется и на продажи. Раньше он проверялся
            // только для покупок, и разгон по продажам ничем не ограничивался.
            if (openOrderCount + placed >= cfg.maxActiveOrders()) {
                return placed;
            }

            if (price == null) {
                ctx.warn("Открыто на крайнем уровне сетки (%d) — закрывать дальше некуда"
                        .formatted(buyLevel));
                continue;
            }
            if (!place(direction.closeSide(), price, buyLevel, sellable)) {
                return placed;
            }
            openSellQuantityByLevel.merge(buyLevel, sellable, BigDecimal::add);
            placed++;
        }
        return placed;
    }

    /**
     * Открывающие заявки на свободных уровнях по ту сторону цены, ближайшие к рынку —
     * первыми. Лонг идёт вниз от ближайшего уровня под ценой, шорт — вверх от
     * ближайшего над ней.
     */
    private void placeMissingBuys(Map<Integer, BotOrderView> openBuys,
                                  Map<Integer, BigDecimal> openSellQuantityByLevel,
                                  Map<Integer, BigDecimal> heldByLevel,
                                  int activeCount) {
        int startLevel = direction.firstOpenLevel(ladder, lastPrice);
        if (startLevel < 0) {
            return;
        }
        int lastLevel = direction.lastOpenLevel(ladder);
        int step = direction.openScanStep();

        for (int level = startLevel; step > 0 ? level <= lastLevel : level >= lastLevel; level += step) {
            if (activeCount >= cfg.maxActiveOrders()) {
                return;
            }
            if (levelIsOccupied(level, openBuys, openSellQuantityByLevel, heldByLevel)) {
                continue;
            }
            BigDecimal price = ladder.priceAt(level);
            // За неблагоприятную границу не заходим: там уже не сетка, а пробой.
            if (price == null || direction.beyondAdverse(price, direction.adverseBound(activeRange))) {
                continue;
            }
            if (!place(direction.openSide(), price, level)) {
                return;
            }
            openBuys.put(level, null);
            activeCount++;
        }
    }

    /**
     * @return false, если ставить дальше нет смысла (упёрлись в лимит)
     */
    private boolean place(OrderSide side, BigDecimal price, int level) {
        return place(side, price, level, sizing.quantityAt(level));
    }

    private boolean place(OrderSide side, BigDecimal price, int level, BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            // Уровень не профинансирован бюджетом (в бюджетных режимах так выглядит
            // верхний, продажный уровень). Ноль до PlaceIntent доходить не должен.
            return true;
        }
        if (levelIsCoolingDown(side, level)) {
            // Этот уровень биржа только что отвергла. Пропускаем именно его,
            // а не весь проход: остальные уровни ни в чём не виноваты.
            return true;
        }
        try {
            // Роль проставляем явно: гейтвей и риск-контроль обязаны знать, набирает
            // эта заявка позицию или закрывает, а из стороны в шорте это не выводится.
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    side, quantity, price, level, OrderPurpose.GRID, direction.roleOf(side)));
            clearPlacementFailure(side, level);
            return true;
        } catch (RiskRejectedException e) {
            // Штатный отказ: лимит сработал так, как задумано.
            ctx.event(BotEventType.RISK_BLOCKED, e.getMessage());
            return false;
        } catch (Exception e) {
            notePlacementFailure(side, level, e);
            // Отказ бывает не только сетевым: так же выглядит закрытие сессии,
            // о котором стрим не сообщил. Спрашиваем биржу, чтобы не повторять
            // ту же заявку каждую минуту до утра.
            refreshTradingStatusAfterRejection();
            return false;
        }
    }

    /**
     * Подтверждает выход вверх и переводит сетку в режим распродажи позиции.
     * Возвращает true, пока старую сетку нельзя пополнять новыми заявками.
     */
    private boolean processUpperBreakout() {
        if (cfg.onUpperBreakout() != GridConfig.UpperBreakoutAction.REPLACE_UPPER) {
            return false;
        }
        if (positionMismatched) {
            upperBreakoutCandidateAt = null;
            return awaitingUpperReplacement;
        }

        BigDecimal favourableBound = direction.favourableBound(activeRange);
        Instant now = ctx.clock().instant();
        if (awaitingUpperReplacement) {
            if (!direction.beyondFavourable(lastPrice, favourableBound)) {
                awaitingUpperReplacement = false;
                upperBreakoutCandidateAt = null;
                upperReplacementStallReported = false;
                halted = false;
                buyingStopped = shouldStopBuying();
                persistState();
                updateSnapshot();
                ctx.event(BotEventType.HOUSEKEEPING,
                        "Цена вернулась в прежний диапазон до его замены. Перестановка вверх отменена.");
                return false;
            }
            cancelOpenBuys();
            if (!tryCompleteUpperReplacement()) {
                ensureOrders(null);
            }
            return true;
        }

        if (lastReplacementAt != null
                && now.isBefore(lastReplacementAt.plusSeconds(cfg.replaceCooldownSeconds()))) {
            upperBreakoutCandidateAt = null;
            return false;
        }

        // Граница берётся по НАПРАВЛЕНИЮ, а не «верхняя»: у шортовой сетки в её пользу
        // работает пробой ВНИЗ — позиция откуплена, диапазон пора двигать следом.
        BigDecimal margin = favourableBound.multiply(cfg.breakoutMarginPct())
                .max(ladder.effectiveStep().divide(BigDecimal.valueOf(2)));
        BigDecimal threshold = direction.favourableThreshold(favourableBound, margin);
        if (!direction.beyondFavourable(lastPrice, favourableBound)) {
            upperBreakoutCandidateAt = null;
            return false;
        }
        if (!direction.beyondFavourable(lastPrice, threshold)) {
            return false;
        }

        if (upperBreakoutCandidateAt == null) {
            upperBreakoutCandidateAt = now;
            ctx.event(BotEventType.HOUSEKEEPING,
                    "GRID: цена %s выше порога пробоя %s, начато подтверждение"
                            .formatted(lastPrice.toPlainString(), threshold.toPlainString()));
            return false;
        }
        if (Duration.between(upperBreakoutCandidateAt, now).getSeconds()
                < cfg.breakoutConfirmSeconds()) {
            return false;
        }

        awaitingUpperReplacement = true;
        upperBreakoutCandidateAt = null;
        upperReplacementStallReported = false;
        buyingStopped = true;
        cancelOpenBuys();
        persistState();
        updateSnapshot();
        ctx.event(BotEventType.RANGE_EXIT,
                ("Пробой верхней границы %s подтверждён по цене %s. Покупки сняты; "
                        + "жду закрытия позиции продажами перед перестановкой.")
                        .formatted(activeRange.upper().toPlainString(), lastPrice.toPlainString()));
        if (!tryCompleteUpperReplacement()) {
            ensureOrders(null);
        }
        return true;
    }

    /** Нижний пробой сначала подтверждается, затем проходит бюджет и валидацию диапазона. */
    private boolean processLowerBreakout() {
        if (cfg.onRangeExit() != GridConfig.RangeExitAction.REPLACE_LOWER) {
            return false;
        }
        if (positionMismatched) {
            lowerBreakoutCandidateAt = null;
            return false;
        }

        BigDecimal adverseBound = direction.adverseBound(activeRange);
        Instant now = ctx.clock().instant();
        if (!direction.beyondAdverse(lastPrice, adverseBound)) {
            lowerBreakoutCandidateAt = null;
            if (lowerBreakoutPaused) {
                lowerBreakoutPaused = false;
                buyingStopped = shouldStopBuying();
                updateSnapshot();
            }
            return false;
        }

        // Против нас работает та граница, которую задаёт направление: лонгу вредит
        // падение под нижнюю, шорту — рост над верхней. Пока это было зашито как
        // «нижняя», шортовая сетка в беде запускала бы машинерию, написанную для
        // противоположного случая.
        BigDecimal margin = adverseBound.multiply(cfg.breakoutMarginPct())
                .max(ladder.effectiveStep().divide(BigDecimal.valueOf(2)));
        BigDecimal threshold = direction.adverseThreshold(adverseBound, margin);
        if (!direction.beyondAdverse(lastPrice, threshold)) {
            return false;
        }

        lowerBreakoutPaused = true;
        buyingStopped = true;
        cancelOpenBuys();
        updateSnapshot();

        if (lastReplacementAt != null
                && now.isBefore(lastReplacementAt.plusSeconds(cfg.replaceCooldownSeconds()))) {
            return true;
        }
        if (lowerBreakoutCandidateAt == null) {
            lowerBreakoutCandidateAt = now;
            ctx.event(BotEventType.HOUSEKEEPING,
                    "GRID: цена %s ниже порога пробоя %s, начато подтверждение"
                            .formatted(lastPrice.toPlainString(), threshold.toPlainString()));
            return true;
        }
        if (Duration.between(lowerBreakoutCandidateAt, now).getSeconds()
                < cfg.breakoutConfirmSeconds()) {
            return true;
        }

        lowerBreakoutCandidateAt = null;
        beginDownwardReplacement();
        return true;
    }

    private void beginDownwardReplacement() {
        // Переворот вместо фиксации убытка — если он разрешён и на него есть право.
        // Отказ здесь не фатален: бот просто продолжает прежним путём, закрывая
        // позицию с убытком. Именно так и задумано — плечо это добавочная
        // возможность, а не замена работающего механизма.
        if (shouldHedgeInsteadOfLiquidating() && beginHedge()) {
            return;
        }
        if (!forcedReplacement && downwardReplacements >= cfg.maxDownwardReplacements()) {
            stopPermanently("Исчерпан лимит перестановок вниз: %d из %d"
                    .formatted(downwardReplacements, cfg.maxDownwardReplacements()));
            return;
        }

        ReconcileResult fresh = reconcileAndCheck();
        if (positionMismatched || fresh.position() == null) {
            failLowerReplacement(
                    "Не удалось получить достоверную позицию перед перестановкой вниз");
            return;
        }

        try {
            if (reconciledPosition.signum() > 0) {
                enforceDownwardBudget(unwindPrice());
                if (halted) {
                    return;
                }
            }
        } catch (Exception e) {
            failLowerReplacement("Не удалось оценить бюджет закрытия позиции: " + e.getMessage());
            return;
        }

        GridRange candidate;
        GridLadder candidateLadder;
        try {
            VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimateAround(
                    ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                    ctx.constraints().minPriceIncrement(), ctx.clock().instant(), lastPrice,
                    GridRange.Origin.ATR_REPLACED_DOWN);
            candidate = estimate.range();
            candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());
            // Размер здесь отбрасываем: он будет пересчитан при коммите перестановки,
            // когда убыток ликвидации уже зафиксирован. Проверка нужна, чтобы отвергнуть
            // нефинансируемый кандидат ДО распродажи позиции, а не после неё.
            GridValidator.validate(cfg, candidate, candidateLadder, ctx.constraints().minPriceIncrement(),
                    activeFees, ctx.constraints().quantityStep(), ctx.execution().maxCapital(),
                    availableBudget(), ctx.carryDailyRate());
        } catch (Exception e) {
            failLowerReplacement("Новый нижний диапазон не прошёл проверку: " + e.getMessage());
            return;
        }

        pendingDownwardRange = candidate;
        try {
            downwardLossBaseline = ctx.realizedPnl();
        } catch (Exception e) {
            pendingDownwardRange = null;
            failLowerReplacement("Не удалось зафиксировать исходный P/L: " + e.getMessage());
            return;
        }
        awaitingDownwardReplacement = true;
        buyingStopped = true;
        try {
            persistState();
        } catch (Exception e) {
            awaitingDownwardReplacement = false;
            pendingDownwardRange = null;
            downwardLossBaseline = null;
            failLowerReplacement("Не удалось сохранить начало ликвидации: " + e.getMessage());
            return;
        }
        updateSnapshot();

        int cancelled = 0;
        try {
            cancelled = ctx.gateway().cancelAll(ctx.execution());
        } catch (Exception e) {
            ctx.error("Не удалось одним запросом снять заявки перед ликвидацией", e);
        }
        ctx.event(BotEventType.RANGE_EXIT,
                ("Пробой нижней границы %s подтверждён по цене %s. Снято заявок: %d; "
                        + "начинаю контролируемое закрытие позиции перед диапазоном %s..%s.")
                        .formatted(activeRange.lower().toPlainString(), lastPrice.toPlainString(), cancelled,
                                candidate.lower().toPlainString(), candidate.upper().toPlainString()));

        manageDownwardLiquidation();
    }

    // ==============================
    // ВОССТАНОВИТЕЛЬНОЕ ПЛЕЧО
    // ==============================

    /**
     * Разворачивает сетку лицом к движению, которое только что стоило денег.
     *
     * В этом и состоял замысел: пробили вниз — дальше торгуем падение шортом; пробили
     * вверх из шорта — возвращаемся в лонг. Без этого новое поколение встаёт к тренду
     * спиной и покупает в падение ровно так же, как покупало до пробоя, — то есть
     * перестановка диапазона лечит симптом, а не причину.
     *
     * Переворот требует маржи: шортовая сетка продаёт то, чего нет. Немаржинальному
     * боту переворачиваться некуда, и он сохраняет прежнее поведение целиком.
     * Живьём — только с явного разрешения, как и всё остальное маржинальное.
     */
    private void flipDirectionIfAllowed() {
        if (!cfg.flipDirectionOnAdverse()) {
            return;
        }
        GridDirection next = direction.opposite();
        if (next == GridDirection.SHORT) {
            if (!ctx.execution().marginEnabled()
                    || !ctx.execution().shortEnabledByInstrument()
                    || (!ctx.execution().dryRun() && !ctx.execution().allowLiveMargin())) {
                // Развернуться нельзя — остаёмся лонгом. Сказать об этом надо: бот
                // продолжит покупать в падение, и человек должен понимать почему.
                ctx.event(BotEventType.RISK_BLOCKED,
                        "Развернуть сетку в шорт нельзя (маржа не разрешена или бумага "
                                + "не шортится) — новое поколение снова лонговое.");
                return;
            }
        }
        direction = next;
    }

    /**
     * Рабочий бюджет за вычетом обеспечения, занятого плечом.
     *
     * При одновременной работе плечо и сетка расходуют ОДНО обеспечение. Считай сетка
     * бюджет как раньше, обе ноги распоряжались бы одними и теми же деньгами: сетка
     * расставила бы уровни под весь бюджет, не зная, что часть его уже заложена
     * за переворот. Упёрлись бы они друг в друга не сразу, а в тот момент, когда
     * обеспечения не хватит на очередную заявку, — то есть под нагрузкой.
     *
     * Вычитается именно ОБЕСПЕЧЕНИЕ, а не номинал плеча: заняты у брокера не все
     * деньги позиции, а доля от неё по ставке риска. Ставка приходит с биржи вместе
     * с лотностью; если брокер её не сообщил, вычитаем номинал целиком — ошибиться
     * здесь можно только в сторону осторожности.
     */
    private BigDecimal availableBudget() {
        BigDecimal budget = cfg.workingBudget(ctx::realizedPnl);
        if (budget == null || hedgeEpisode == null) {
            return budget;
        }
        BigDecimal notional = hedgeEpisode.hedgeQuantity().multiply(hedgeEpisode.entryPrice());
        BigDecimal rate = ctx.constraints().shortInitialMarginRate();
        BigDecimal locked = rate == null || rate.signum() <= 0
                ? notional
                : notional.multiply(rate);

        BigDecimal left = budget.subtract(locked);
        if (left.signum() <= 0) {
            // Плечо съело бюджет целиком. Ноль честнее отрицательной величины:
            // сетка на него просто не выставит заявок, а отрицательный бюджет
            // где-нибудь ниже превратился бы в отрицательный размер заявки.
            return BigDecimal.ZERO;
        }
        return left;
    }

    /**
     * Есть ли у бота право переворачивать позицию вместо её закрытия.
     *
     * Условий много, и каждое отсекает случай, в котором переворот означал бы
     * непокрытую позицию без надзора. Ни одно из них не «на всякий случай»:
     * маржа — потому что шорта иначе не бывает; бумажный режим — потому что
     * живьём этот путь ещё не проверен; лимит эпизодов — потому что рекурсивный
     * переворот и есть способ проиграть счёт целиком.
     */
    private boolean shouldHedgeInsteadOfLiquidating() {
        if (cfg.onAdverseBreakout() != GridConfig.AdverseBreakoutAction.HEDGE_AND_RECOVER) {
            return false;
        }
        if (hedgeEpisode != null) {
            // Эпизод уже идёт: второй поверх первого — это и есть рекурсия.
            return false;
        }
        if (hedgeEpisodesUsed >= cfg.maxHedgeEpisodes()) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    ("Лимит переворотов поколения исчерпан (%d из %d) — закрываю позицию "
                            + "с фиксацией убытка. Каждый следующий переворот умножает "
                            + "экспозицию, и это тот предел, который был задан заранее.")
                            .formatted(hedgeEpisodesUsed, cfg.maxHedgeEpisodes()));
            return false;
        }
        if (!ctx.execution().marginEnabled()) {
            return false;
        }
        if (!ctx.execution().shortEnabledByInstrument()) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Брокер не шортит эту бумагу — переворот невозможен, закрываю позицию.");
            return false;
        }
        if (!ctx.execution().dryRun() && !ctx.execution().allowLiveMargin()) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Переворот живьём не разрешён (allowLiveMargin выключен) — "
                            + "закрываю позицию с фиксацией убытка.");
            return false;
        }
        if (!ctx.execution().dryRun()) {
            // Первая живая непокрытая позиция — событие, о котором человек должен
            // узнать сразу, а не обнаружить его в отчёте через день.
            ctx.event(BotEventLevel.WARN, BotEventType.RANGE_EXIT,
                    "Открываю НЕПОКРЫТУЮ позицию на настоящие деньги. Убыток по ней сверху "
                            + "ничем не ограничен, брокер вправе закрыть её сам, а ночью "
                            + "защитной заявки в стакане не будет: GTC у T-Invest нет.");
        }
        return true;
    }

    /**
     * Переворачивает позицию с множителем.
     *
     * Считает план ДО единой заявки и отказывается, если он не сходится: цена
     * безубытка не по ту сторону, убыток неподъёмен, размер не влезает в потолки.
     * Отказ возвращает false, и вызывающий продолжает обычной ликвидацией —
     * молча уменьшать множитель нельзя, это изменило бы безубыток, о котором
     * человек уже думает.
     *
     * @return true, если переворот начат
     */
    private boolean beginHedge() {
        ReconcileResult fresh = reconcileAndCheck();
        if (positionMismatched || fresh.position() == null || fresh.position().signum() == 0) {
            return false;
        }

        BigDecimal price;
        try {
            price = unwindPrice();
        } catch (Exception e) {
            ctx.error("Не удалось получить цену для переворота позиции", e);
            return false;
        }

        Inventory inventory = ctx.inventory();
        HedgeMath.Plan plan = HedgeMath.plan(
                direction, fresh.position().abs(), inventory.costBasisOpen(), price,
                activeFees.makerRateFor(direction.closeSide()), cfg.hedgeMultiplier(),
                ctx.carryDailyRate(), cfg.maxHedgeHoldDays());

        if (!plan.accepted()) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Переворот невозможен: " + plan.refusal() + " Закрываю позицию как обычно.");
            return false;
        }
        if (!hedgeFitsCeilings(plan)) {
            return false;
        }

        UUID episodeId = UUID.randomUUID();
        try {
            // Одной заявкой: часть закрывает старую позицию, остаток открывает плечо.
            // Двумя заявками этого делать нельзя — между ними позиция оказалась бы
            // закрытой, и рынок успел бы уйти ровно в тот момент, ради которого
            // переворот и затевался.
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    direction.closeSide(), plan.totalQuantity(), price, null,
                    OrderPurpose.HEDGE, GridRole.OPEN));
        } catch (Exception e) {
            ctx.error("Заявку переворота выставить не удалось", e);
            return false;
        }

        Instant now = ctx.clock().instant();
        hedgeEpisode = new HedgeEpisode(
                episodeId, direction.opposite(), now, price, plan.hedgeQuantity(),
                plan.targetPrice(), cfg.hedgeMultiplier(), plan.realizedOnClose(),
                now.plus(Duration.ofDays(cfg.maxHedgeHoldDays())),
                stopPriceFor(price));
        hedgeEpisodesUsed++;
        persistState();
        updateSnapshot();

        // Отчёт не должен мешать торговле: сорвавшаяся запись — повод для строки
        // в журнале, но не для отката переворота, который уже случился на бирже.
        try {
            ctx.openRecoveryEpisode(gridGeneration, episodeId, direction.opposite().name(),
                    price, plan.targetPrice(), cfg.hedgeMultiplier(), now);
        } catch (Exception e) {
            ctx.error("Переворот выполнен, но строку эпизода записать не удалось", e);
        }

        ctx.event(BotEventType.RANGE_EXIT,
                ("Позиция перевёрнута ×%s по %s: закрыто с убытком %s, открыто плечо %s. "
                        + "Цель безубытка %s, срок до %s%s. Плечо и сетка %s.")
                        .formatted(cfg.hedgeMultiplier().toPlainString(), price.toPlainString(),
                                plain(plan.realizedOnClose()), plainQuantity(plan.hedgeQuantity()),
                                plan.targetPrice().toPlainString(),
                                hedgeEpisode.deadline(),
                                hedgeEpisode.stopPrice() == null ? ""
                                        : ", стоп " + hedgeEpisode.stopPrice().toPlainString(),
                                cfg.hedgeAndGridConcurrent() ? "работают одновременно"
                                        : "работают по очереди"));
        return true;
    }

    /** Размер плеча обязан влезать в те же потолки, что и любая короткая позиция. */
    private boolean hedgeFitsCeilings(HedgeMath.Plan plan) {
        BigDecimal maxQuantity = ctx.execution().maxShortQuantity();
        BigDecimal maxNotional = ctx.execution().maxShortNotional();

        if (maxQuantity == null || maxQuantity.signum() <= 0
                || maxNotional == null || maxNotional.signum() <= 0) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Переворот отменён: не заданы потолки короткой позиции. "
                            + "Закрываю позицию с фиксацией убытка.");
            return false;
        }
        if (plan.hedgeQuantity().compareTo(maxQuantity) > 0
                || plan.hedgeNotional().compareTo(maxNotional) > 0) {
            // Множитель НЕ уменьшаем: цена безубытка посчитана именно под него,
            // и тихая подмена дала бы человеку не тот план, который он видел.
            ctx.event(BotEventType.RISK_BLOCKED,
                    ("Переворот ×%s не влезает в потолки: плечо %s на %s при пределах %s и %s. "
                            + "Закрываю позицию с фиксацией убытка — множитель молча "
                            + "не уменьшаю, он определяет цену безубытка.")
                            .formatted(cfg.hedgeMultiplier().toPlainString(),
                                    plainQuantity(plan.hedgeQuantity()), plain(plan.hedgeNotional()),
                                    plainQuantity(maxQuantity), plain(maxNotional)));
            return false;
        }
        return true;
    }

    /** Цена, за которой эпизод признаётся неудавшимся. Null — стоп не задан. */
    private BigDecimal stopPriceFor(BigDecimal entryPrice) {
        BigDecimal pct = cfg.hedgeStopLossPct();
        if (pct == null || pct.signum() <= 0) {
            return null;
        }
        // Стоп всегда по ту сторону входа, в которую мы НЕ рассчитывали: плечо
        // после лонга — это шорт, и губит его рост.
        return direction == GridDirection.LONG
                ? entryPrice.multiply(BigDecimal.ONE.add(pct))
                : entryPrice.multiply(BigDecimal.ONE.subtract(pct));
    }

    /**
     * Ведёт открытый эпизод: цель, стоп, срок.
     *
     * Порядок проверок намеренный. Сначала цель — если её достигли, закрываемся
     * в плюс и остальное неважно. Потом стоп: он означает, что расчёт не сбылся,
     * и признать это лучше раньше. Срок последним: он крайний рубеж, а не план.
     */
    private void manageHedgeLeg() {
        if (hedgeEpisode == null || halted || positionMismatched || lastPrice == null) {
            return;
        }
        if (!limitOrdersAvailable) {
            return;
        }

        if (hedgeEpisode.targetReached(lastPrice)) {
            closeHedge("цель достигнута", lastPrice);
            return;
        }
        if (hedgeEpisode.stopped(lastPrice)) {
            closeHedge("цена ушла за стоп", lastPrice);
            return;
        }
        if (hedgeEpisode.expired(ctx.clock().instant())) {
            closeHedge("истёк срок удержания", lastPrice);
            return;
        }
        ensureHedgeExitOrder();
    }

    /**
     * Держит выставленной заявку выхода по расчётной цене.
     *
     * Заявка стоит в стакане, а не ждёт, пока цена коснётся цели на нашем тике:
     * между тиками цена вполне успевает сходить туда и обратно, и ожидание
     * означало бы упустить ровно то движение, ради которого всё затевалось.
     */
    private void ensureHedgeExitOrder() {
        boolean alreadyPlaced = ctx.gateway().openOrders(ctx.botId()).stream()
                .anyMatch(o -> o.purpose() == OrderPurpose.RECOVERY);
        if (alreadyPlaced) {
            return;
        }
        try {
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    hedgeEpisode.direction().closeSide(), hedgeEpisode.hedgeQuantity(),
                    hedgeEpisode.targetPrice(), null, OrderPurpose.RECOVERY, GridRole.CLOSE));
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Выставлен выход из плеча: %s по %s"
                            .formatted(plainQuantity(hedgeEpisode.hedgeQuantity()),
                                    hedgeEpisode.targetPrice().toPlainString()));
        } catch (RiskRejectedException e) {
            ctx.event(BotEventType.RISK_BLOCKED, "Выход из плеча пока запрещён: " + e.getMessage());
        } catch (Exception e) {
            ctx.error("Не удалось выставить выход из плеча", e);
        }
    }

    /** Закрывает эпизод по рынку и признаёт результат, каким бы он ни был. */
    private void closeHedge(String reason, BigDecimal price) {
        HedgeEpisode episode = hedgeEpisode;
        for (BotOrderView order : ctx.gateway().openOrders(ctx.botId())) {
            if (order.purpose() != OrderPurpose.RECOVERY) {
                continue;
            }
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять прежний выход из плеча", e);
                return;
            }
        }

        BigDecimal exitPrice;
        try {
            exitPrice = unwindPrice();
        } catch (Exception e) {
            exitPrice = price;
        }
        try {
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    episode.direction().closeSide(), episode.hedgeQuantity(), exitPrice, null,
                    OrderPurpose.RECOVERY, GridRole.CLOSE));
        } catch (Exception e) {
            ctx.error("Не удалось закрыть плечо по рынку", e);
            return;
        }

        hedgeEpisode = null;
        persistState();
        updateSnapshot();
        try {
            ctx.closeRecoveryEpisode(episode.episodeId(), ctx.clock().instant());
        } catch (Exception e) {
            ctx.error("Плечо закрыто, но строку эпизода закрыть не удалось", e);
        }
        ctx.event(BotEventType.RANGE_EXIT,
                ("Плечо закрывается по %s: %s. Убыток входа был %s — окупился он или нет, "
                        + "видно по результату эпизода в таблице поколений.")
                        .formatted(exitPrice.toPlainString(), reason, plain(episode.lossAtEntry())));
    }

    /** Поддерживает одну агрессивную SELL на фактический остаток позиции. */
    private void manageDownwardLiquidation() {
        if (!awaitingDownwardReplacement || halted || positionMismatched
                || reconciledPosition == null) {
            return;
        }
        // Тот же допуск, что и при перестановке вверх: остаток, на который биржа
        // не примет заявки, ликвидации не «допродать», а ждать его — ждать вечно.
        if (positionIsFlat(reconciledPosition)) {
            for (BotOrderView order : gridOrders()) {
                try {
                    ctx.gateway().cancel(ctx.execution(), order.id());
                } catch (Exception e) {
                    ctx.error("Не удалось снять остаточную заявку после ликвидации", e);
                }
            }
            if (gridOrders().isEmpty()) {
                tryCompleteDownwardReplacement();
            }
            return;
        }
        if (reconciledPosition.signum() < 0) {
            stopPermanently("Сверка показала короткую позицию во время ликвидации: "
                    + reconciledPosition.toPlainString());
            return;
        }
        if (!limitOrdersAvailable) {
            return;
        }

        BigDecimal bid;
        try {
            bid = unwindPrice();
            enforceDownwardBudget(bid);
        } catch (Exception e) {
            ctx.error("Не удалось обновить лучший бид для ликвидации", e);
            return;
        }
        if (halted) {
            return;
        }
        placeOrRepriceLiquidation(bid);
    }

    private void placeOrRepriceLiquidation(BigDecimal bid) {
        // Заявки сетки, без пыли: её продажа к ликвидации отношения не имеет и
        // снимать её нельзя. Раньше «продажа без уровня» означала ликвидацию —
        // с появлением второй такой заявки эта договорённость перестала работать.
        List<BotOrderView> open = gridOrders();
        BotOrderView liquidation = open.stream()
                .filter(o -> o.purpose() == OrderPurpose.LIQUIDATION)
                .findFirst()
                .orElse(null);

        boolean onlyLiquidation = liquidation != null && open.size() == 1;
        if (onlyLiquidation && liquidation.limitPrice() != null
                && liquidation.limitPrice().compareTo(bid) <= 0
                && liquidation.remainingQuantity().compareTo(reconciledPosition) == 0) {
            return;
        }

        for (BotOrderView order : open) {
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять заявку перед переоценкой ликвидации", e);
            }
        }
        ReconcileResult afterCancel = reconcileAndCheck();
        if (positionMismatched || afterCancel.position() == null
                || afterCancel.position().signum() <= 0) {
            manageDownwardLiquidation();
            return;
        }
        List<BotOrderView> stillOpen = gridOrders();
        if (!stillOpen.isEmpty()) {
            // Раньше здесь был молчаливый return, и это скрывало тупик: заявки, которые
            // не удалось ни снять, ни разрешить, оставались «открытыми», ликвидация
            // ждала пустого списка и не выставляла продажу вообще — при том что бот
            // считался закрывающим позицию. Ждать по-прежнему правильно, но молча — нет.
            // Сообщаем один раз за эпизод: тик частый, а журнал событий читают люди.
            if (!liquidationStallReported) {
                liquidationStallReported = true;
                ctx.event(BotEventType.RISK_BLOCKED,
                        "Ликвидация ждёт снятия заявок (%d): %s".formatted(
                                stillOpen.size(),
                                stillOpen.stream()
                                        .map(o -> o.side() + " " + o.status())
                                        .collect(Collectors.joining(", "))));
            }
            return;
        }
        liquidationStallReported = false;

        BigDecimal freshBid;
        try {
            freshBid = unwindPrice();
            enforceDownwardBudget(freshBid);
        } catch (Exception e) {
            ctx.error("Не удалось обновить лучший бид после снятия ликвидационной заявки", e);
            return;
        }
        if (halted) {
            return;
        }
        try {
            // Модуль: у шортовой сетки позиция отрицательна, а размер заявки — всегда
            // положительное количество. Сторону задаёт направление, а не знак позиции.
            BigDecimal quantity = afterCancel.position().abs();
            ctx.gateway().placeLimit(ctx.execution(), new PlaceIntent(
                    direction.closeSide(), quantity, freshBid, null,
                    OrderPurpose.LIQUIDATION, GridRole.CLOSE));
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Ликвидационная %s: %s по %s"
                            .formatted(direction.closeSide(), plainQuantity(quantity),
                                    freshBid.toPlainString()));
        } catch (RiskRejectedException e) {
            ctx.event(BotEventType.RISK_BLOCKED,
                    "Ликвидационная заявка пока запрещена лимитом: " + e.getMessage());
        } catch (Exception e) {
            ctx.error("Не удалось выставить ликвидационную заявку", e);
        }
    }

    private void enforceDownwardBudget(BigDecimal bid) {
        // Считаем всегда, даже когда потолок снят: внутри живёт сверка позиции книги
        // с биржевой, и пропустить её нельзя ни при каком разрешении оператора.
        BigDecimal projected = projectedDownwardLoss(bid);
        if (forcedReplacement) {
            return;
        }
        if (projected.compareTo(cfg.maxRealizedLoss()) > 0) {
            stopPermanently(("Перестановка вниз остановлена бюджетом убытка: прогноз %s, "
                    + "потолок %s").formatted(projected.toPlainString(),
                    cfg.maxRealizedLoss().toPlainString()));
        }
    }

    private BigDecimal projectedDownwardLoss(BigDecimal bid) {
        Inventory inventory = ctx.inventory();
        BigDecimal position = reconciledPosition == null ? BigDecimal.ZERO : reconciledPosition;
        // compareTo, а не !=: книга и сверка приходят с разной шкалой BigDecimal.
        if (inventory.openQuantity().compareTo(position) != 0) {
            stopPermanently(("Позиция книги %s не совпадает со сверенной позицией %s. "
                    + "Автоматически фиксировать убыток нельзя.")
                    .formatted(plainQuantity(inventory.openQuantity()), plainQuantity(position)));
            return cfg.maxRealizedLoss().add(BigDecimal.ONE);
        }

        BigDecimal currentLoss = downwardLossBaseline == null
                ? BigDecimal.ZERO
                : downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO);
        // Множителя нет: позиция уже в единицах базового актива, цена — за единицу.
        BigDecimal gross = bid.multiply(position);
        BigDecimal sellFee = gross.multiply(activeFees.makerSellRate());
        BigDecimal remainingLoss = inventory.costBasisOpen()
                .subtract(gross).add(sellFee).max(BigDecimal.ZERO);
        return realizedDownwardLoss.add(currentLoss).add(remainingLoss);
    }

    /**
     * Цена, по которой позиция закроется прямо сейчас: лучший бид для лонга,
     * лучший аск для шорта. Сторону выбирает направление — здесь её знать неоткуда.
     */
    private BigDecimal unwindPrice() {
        OrderBook book = ctx.exchange().marketData()
                .getOrderBook(ctx.execution().instrumentId(), 1);
        BigDecimal price = direction.unwindPrice(book);
        if (price == null) {
            throw new IllegalStateException(
                    "Биржа не вернула цену для закрытия позиции (" + direction + ")");
        }
        return price;
    }

    private void tryCompleteDownwardReplacement() {
        if (!awaitingDownwardReplacement || positionMismatched || pendingDownwardRange == null
                || reconciledPosition == null || reconciledPosition.signum() != 0
                || !ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
            return;
        }

        BigDecimal completedLoss = downwardLossBaseline == null
                ? BigDecimal.ZERO
                : downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO);
        realizedDownwardLoss = realizedDownwardLoss.add(completedLoss);
        if (!forcedReplacement && realizedDownwardLoss.compareTo(cfg.maxRealizedLoss()) > 0) {
            downwardLossBaseline = null;
            stopPermanently(("Фактический накопленный убыток %s превысил потолок %s")
                    .formatted(realizedDownwardLoss.toPlainString(),
                            cfg.maxRealizedLoss().toPlainString()));
            return;
        }

        GridRange previous = activeRange;
        GridLadder previousLadder = ladder;
        GridSizing previousSizing = sizing;
        BigDecimal previousBudget = activeBudget;
        long previousGeneration = gridGeneration;
        int previousDownwardReplacements = downwardReplacements;
        BigDecimal previousRealizedDownwardLoss = realizedDownwardLoss.subtract(completedLoss);
        BigDecimal previousLossBaseline = downwardLossBaseline;
        Instant previousReplacementAt = lastReplacementAt;
        boolean forced = forcedReplacement;
        GridRange candidate = pendingDownwardRange;

        GridLadder candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());
        GridSizing candidateSizing;
        BigDecimal candidateBudget;
        try {
            // Пересчёт обязателен именно здесь: принудительная ликвидация только что
            // зафиксировала убыток, и при реинвестировании прибыли рабочий бюджет стал
            // меньше. Переиспользование старого размера означало бы перерасход бюджета.
            candidateBudget = availableBudget();
            candidateSizing = GridValidator.validate(cfg, candidate, candidateLadder,
                    ctx.constraints().minPriceIncrement(), activeFees, ctx.constraints().quantityStep(),
                    ctx.execution().maxCapital(), candidateBudget, ctx.carryDailyRate()).sizing();
        } catch (Exception e) {
            failLowerReplacement("Новый нижний диапазон не прошёл проверку: " + e.getMessage());
            return;
        }

        activeRange = candidate;
        ladder = candidateLadder;
        sizing = candidateSizing;
        activeBudget = candidateBudget;
        gridGeneration++;
        // Лимит переворотов принадлежит ПОКОЛЕНИЮ: новая сетка начинает с чистого
        // счётчика, иначе он был бы одноразовым на всю жизнь бота.
        hedgeEpisodesUsed = 0;
        GridDirection previousDirection = direction;
        flipDirectionIfAllowed();
        if (forced) {
            // Оператор принял убыток целиком и вернул бота в строй — значит и лимиты
            // риска начинают отсчёт заново, иначе кнопка была бы одноразовой:
            // выбранный потолок продолжал бы запрещать любую следующую перестановку.
            //
            // История при этом не теряется: убыток остаётся в книге операций, а по
            // поколениям он виден стоимостью перехода в статистике поколений сетки.
            realizedDownwardLoss = BigDecimal.ZERO;
            downwardReplacements = 0;
        } else {
            downwardReplacements++;
        }
        forcedReplacement = false;
        lastReplacementAt = ctx.clock().instant();
        awaitingDownwardReplacement = false;
        pendingDownwardRange = null;
        downwardLossBaseline = null;
        lowerBreakoutPaused = false;
        buyingStopped = shouldStopBuying();
        halted = false;
        try {
            persistState();
        } catch (Exception e) {
            activeRange = previous;
            ladder = previousLadder;
            sizing = previousSizing;
            activeBudget = previousBudget;
            gridGeneration = previousGeneration;
            downwardReplacements = previousDownwardReplacements;
            realizedDownwardLoss = previousRealizedDownwardLoss;
            downwardLossBaseline = previousLossBaseline;
            lastReplacementAt = previousReplacementAt;
            forcedReplacement = forced;
            awaitingDownwardReplacement = true;
            pendingDownwardRange = candidate;
            failLowerReplacement("Не удалось сохранить новый нижний диапазон: " + e.getMessage());
            return;
        }

        updateSnapshot();
        if (direction != previousDirection) {
            ctx.event(BotEventType.GRID_REPLACED,
                    "Сетка развёрнута: %s → %s. Дальше торгуем движение, а не против него."
                            .formatted(previousDirection, direction));
        }
        String note = forced
                ? ("GRID поколение %d: сетка перестроена по команде оператора. Позиция закрыта "
                        + "по рынку, зафиксирован убыток %s; диапазон %s..%s заменён на %s..%s. "
                        + "Счётчик риск-бюджета обнулён: перестановки снова считаются с нуля "
                        + "(потолок %s, лимит %d). На P/L бота обнуление не влияет — "
                        + "убыток остаётся в книге операций.")
                        .formatted(gridGeneration, completedLoss.toPlainString(),
                                previous.lower().toPlainString(), previous.upper().toPlainString(),
                                activeRange.lower().toPlainString(), activeRange.upper().toPlainString(),
                                cfg.maxRealizedLoss().toPlainString(), cfg.maxDownwardReplacements())
                : ("GRID поколение %d: диапазон %s..%s заменён вниз на %s..%s; "
                        + "убыток перестановки %s, накоплено %s, использовано %d из %d")
                        .formatted(gridGeneration,
                                previous.lower().toPlainString(), previous.upper().toPlainString(),
                                activeRange.lower().toPlainString(), activeRange.upper().toPlainString(),
                                completedLoss.toPlainString(), realizedDownwardLoss.toPlainString(),
                                downwardReplacements, cfg.maxDownwardReplacements());
        note = withClosedGenerationSummary(note);
        try {
            ctx.ledgerMarker(LedgerEntryType.GRID_REPLACED, note);
        } catch (Exception e) {
            ctx.error("Диапазон заменён, но отметку GRID_REPLACED не удалось записать в книгу", e);
        }
        ctx.event(BotEventType.GRID_REPLACED, note);
        ensureOrders(null);
    }

    /**
     * Список обязан совпадать с разбором в {@link #onCommand}: разъехавшись, они дают
     * худший вид отказа — команда реализована и работает, но до стратегии не доходит.
     * Ровно так и случилось с плановой остановкой: и кнопка, и контроллер, и
     * {@link #scheduleStop()} были на месте, а здесь остался один пункт, и оператор
     * получал отказ про перестройку сетки в ответ на нажатие «Остановить».
     *
     * Автодиапазон требуется только перестройке: она СТРОИТ новый диапазон по ATR,
     * а ручному боту его задаёт человек, и подменять его нечем. Плановая остановка
     * ничего не строит — она снимает покупки и ждёт продажи, что осмысленно
     * для любой сетки.
     */
    @Override
    public boolean supports(StrategyCommand command) {
        if (!cfg.enabled()) {
            return false;
        }
        return switch (command) {
            case FORCE_GRID_REPLACEMENT -> cfg.autoRange();
            case SCHEDULE_STOP, CANCEL_SCHEDULED_STOP -> true;
            // В FIXED_QUANTITY размер задан в штуках, и бюджет в расчёте не участвует:
            // менять его молча значило бы обещать эффект, которого не будет.
            case SET_BUDGET -> cfg.budgetSized();
        };
    }

    @Override
    public void onCommand(CommandRequest request) {
        switch (request.command()) {
            case FORCE_GRID_REPLACEMENT -> forceReplacement();
            case SCHEDULE_STOP -> scheduleStop();
            case CANCEL_SCHEDULED_STOP -> cancelScheduledStop();
            case SET_BUDGET -> applyBudget(request.requireAmount());
            default -> throw new UnsupportedOperationException(
                    "GRID не поддерживает команду " + request.command());
        }
    }

    /**
     * Плановая остановка: снять покупки и дождаться, пока распродастся позиция.
     *
     * Покупки снимаются сразу и целиком — это и есть «уже не начавшие выполняться
     * заявки». Встречные продажи остаются: они и закрывают позицию, каждая по своей
     * цене уровня. Бот выключится сам, когда продавать станет нечего.
     *
     * Ждать может долго: продажи стоят по ценам сетки, а не по рынку. Это осознанный
     * размен — цена вместо скорости. Кому нужна скорость, у того есть перестройка
     * с фиксацией убытка, которая закрывает позицию по рынку.
     */
    private void scheduleStop() {
        if (stopped || ctx == null) {
            throw new IllegalStateException("Бот не запущен");
        }
        if (stopScheduled) {
            return;
        }
        stopScheduled = true;
        buyingStopped = true;
        cancelOpenBuys();
        persistState();
        updateSnapshot();

        ctx.event(BotEventType.RANGE_EXIT,
                "Запланирована остановка: покупки сняты, жду исполнения продаж. "
                        + "Бот выключится сам, когда позиция закроется.");

        // Возможно, продавать уже нечего — тогда незачем ждать вообще.
        completeScheduledStopIfDone();
    }

    /** Отмена решения: бот возвращается в обычную работу с той же сеткой. */
    private void cancelScheduledStop() {
        if (!stopScheduled) {
            return;
        }
        stopScheduled = false;
        stopRequested = false;
        buyingStopped = shouldStopBuying();
        persistState();
        updateSnapshot();
        ctx.event(BotEventType.HOUSEKEEPING,
                "Плановая остановка отменена — бот возвращается к работе.");
        ensureOrders(null);
    }

    /**
     * Выключает бота, когда продавать больше нечего.
     *
     * Условий два. Позиция закрыта — с той же оговоркой про непродаваемый остаток,
     * что и везде: точного нуля на бирже с комиссией монетой не бывает. И на бирже
     * не осталось наших заявок, включая продажу пыли: бота после плановой остановки
     * должно быть можно удалить, а удалению мешает любая живая заявка.
     */
    private void completeScheduledStopIfDone() {
        if (!stopScheduled || stopRequested || halted
                || positionMismatched || reconciledPosition == null) {
            return;
        }
        if (!positionIsFlat(reconciledPosition)) {
            return;
        }
        for (BotOrderView order : ctx.gateway().openOrders(ctx.botId())) {
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять заявку при плановой остановке", e);
                return;
            }
        }
        if (!ctx.gateway().openOrders(ctx.botId()).isEmpty()) {
            // Что-то не снялось — попробуем на следующем проходе, а не выключимся
            // с живой заявкой на бирже.
            return;
        }
        // Остановку просим ровно один раз: проверка живёт и в тике, и в ensureOrders,
        // и оба пути законно срабатывают на одном проходе.
        stopRequested = true;
        ctx.event(BotEventType.RANGE_EXIT,
                "Плановая остановка завершена: позиция закрыта, заявок не осталось. "
                        + "Бота можно безопасно удалить.");
        ctx.requestStop("Плановая остановка завершена");
    }

    /**
     * Ручная перестановка: оператор принимает убыток и возвращает бота в работу.
     *
     * Нужна из-за штатного тупика. Исчерпав бюджет убытка, бот перестаёт переставлять
     * сетку — правильно, ради этого бюджет и задавался. Но если цена к этому моменту
     * ушла ниже всего диапазона, он не может ни купить (все уровни выше рынка), ни
     * продать (позиция куплена дороже) — и остаётся формально работающим, ничего
     * не делая, без единого способа вернуться в строй самостоятельно.
     *
     * Отдельного пути ликвидации здесь нет намеренно: команда лишь снимает потолок
     * и запускает тот же самый механизм, что и подтверждённый пробой. Второй путь
     * закрытия позиции означал бы второй набор ошибок в самом дорогом месте.
     */
    private void forceReplacement() {
        if (stopped || ctx == null || ladder == null || activeRange == null) {
            throw new IllegalStateException("Стратегия ещё не готова — повторите через несколько секунд");
        }
        if (positionMismatched) {
            throw new IllegalStateException(
                    "Позиция журнала расходится с биржей. Пока расхождение не устранено, "
                            + "закрывать позицию по рынку нельзя: неизвестно, сколько её на самом деле.");
        }
        // Перестановку вверх кнопка раньше отвергала: «дождитесь завершения». Но ждать
        // её можно бесконечно — она завершится только продажами по сетке, а если
        // остаток позиции им не по зубам (кратен шагу, но дешевле минимальной суммы
        // заявки), продать его нечем, и другого рычага у оператора нет: рестарт
        // не помогает, флаг восстанавливается из сохранённого состояния. Отменяем
        // ожидание и закрываем позицию по рынку тем же путём, что и при пробое вниз.
        if (awaitingUpperReplacement) {
            awaitingUpperReplacement = false;
            upperReplacementStallReported = false;
        }

        forcedReplacement = true;
        // Снимаем аварийную остановку: команда оператора — это и есть то решение,
        // из-за отсутствия которого бот встал.
        halted = false;
        lowerBreakoutCandidateAt = null;
        upperBreakoutCandidateAt = null;
        liquidationStallReported = false;

        ctx.event(BotEventType.RANGE_EXIT,
                "Оператор запросил перестройку сетки с фиксацией убытка: закрываю позицию "
                        + "по рынку, потолок убытка и лимит перестановок для этой операции сняты.");

        if (awaitingDownwardReplacement) {
            // Ликвидация уже шла и была остановлена потолком — продолжаем её без него,
            // а не начинаем заново: заново означало бы новый диапазон-кандидат и
            // потерю уже проданной части.
            persistState();
            updateSnapshot();
            manageDownwardLiquidation();
            return;
        }

        seedLastPrice();
        beginDownwardReplacement();
    }

    /**
     * Новый бюджет без остановки бота.
     *
     * Смысл операции — доливка или вывод денег, а не перестройка сетки: диапазон,
     * поколение и открытые циклы остаются на месте, меняется только размер БУДУЩИХ
     * покупок. Купленное перекроить нельзя, и продавать надо ровно то, что куплено,
     * поэтому уровень с незакрытым циклом доживает его прежним размером.
     *
     * Проверка идёт до подмены и по тем же правилам, что и на старте: бюджет, которого
     * не хватает на шаг количества на каждом уровне, обязан быть отвергнут ЗДЕСЬ, с
     * понятным текстом, а не превратиться в молчащего бота. Отказ уходит исключением —
     * его перехватывает хендлер и показывает оператору.
     */
    private void applyBudget(BigDecimal newBudget) {
        if (stopped || ctx == null || ladder == null || activeRange == null) {
            throw new IllegalStateException("Стратегия ещё не готова — повторите через несколько секунд");
        }
        if (!cfg.budgetSized()) {
            throw new IllegalStateException(
                    "У бота фиксированный размер заявки — бюджет в расчёте размеров не участвует.");
        }
        if (positionMismatched) {
            throw new IllegalStateException(
                    "Позиция журнала расходится с биржей. Пока расхождение не устранено, менять "
                            + "размеры заявок нельзя: неизвестно, сколько позиции на самом деле.");
        }

        GridConfig candidate = cfg.withBudget(newBudget);
        BigDecimal candidateBudget = candidate.workingBudget(ctx::realizedPnl);
        // Бросает с человеческим текстом: не хватило на уровень, не окупается комиссия,
        // бюджет выше потолка капитала. Действующая конфигурация при этом не тронута.
        GridSizing candidateSizing = GridValidator.validate(candidate, activeRange, ladder,
                ctx.constraints().minPriceIncrement(), activeFees, ctx.constraints().quantityStep(),
                ctx.execution().maxCapital(), candidateBudget, ctx.carryDailyRate()).sizing();

        BigDecimal previousBudget = activeBudget;
        String previousSizing = sizingSummary();

        cfg = candidate;
        sizing = candidateSizing;
        activeBudget = candidateBudget;
        resizeRequested = true;
        updateSnapshot();

        String note = "Бюджет изменён: %s → %s. Размер заявки: было %s, стало %s"
                .formatted(plain(previousBudget), plain(activeBudget), previousSizing, sizingSummary());
        try {
            // Внешняя доливка и вывод — не прибыль и не убыток бота, в P/L им места нет.
            // Но без отметки в книге история «сколько в бота вложено» врёт ровно на них.
            ctx.ledgerMarker(LedgerEntryType.BUDGET_CHANGED, note);
        } catch (Exception e) {
            ctx.error("Бюджет изменён, но отметку в книге записать не удалось", e);
        }
        ctx.event(BotEventType.HOUSEKEEPING, note);
        ensureOrders(null);
    }

    /**
     * Приводит уже выставленные покупки к действующему размеру заявки.
     *
     * Трогаем только заявки, которые ещё не начали исполняться. Снятие частично
     * исполненной покупки оставило бы на уровне позицию размером «сколько успело»,
     * и цикл уровня закрывался бы продажей другого объёма — то есть смена бюджета
     * молча меняла бы уже начатую сделку.
     *
     * Продажи не трогаем никогда: их объём берётся из фактически купленного, а не из
     * бюджета, и переразмерить их значит либо оставить хвост непроданным, либо
     * выставить необеспеченную заявку.
     *
     * @return сколько заявок снято — на столько же освободилось место под лимитом
     */
    private int resizeOpenBuys(Map<Integer, BotOrderView> openBuys) {
        int cancelled = 0;
        boolean complete = true;
        for (Iterator<Map.Entry<Integer, BotOrderView>> it = openBuys.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, BotOrderView> entry = it.next();
            BotOrderView order = entry.getValue();
            if (order == null || (order.executedQuantity() != null
                    && order.executedQuantity().signum() > 0)) {
                continue;
            }
            BigDecimal planned = sizing.quantityAt(entry.getKey());
            if (planned == null || planned.signum() <= 0) {
                continue;
            }
            // Сравниваем с тем количеством, которое реально уйдёт на биржу: гейтвей
            // округляет заявку вниз к шагу. Без этого округление выглядело бы вечным
            // расхождением, и заявка снималась бы и ставилась заново каждый проход.
            BigDecimal tradable = ctx.execution().quantizeDown(planned);
            if (tradable.signum() <= 0 || order.remainingQuantity().compareTo(tradable) == 0) {
                continue;
            }
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
                it.remove();
                cancelled++;
            } catch (Exception e) {
                complete = false;
                ctx.error("Не удалось снять покупку уровня %d для смены размера"
                        .formatted(entry.getKey()), e);
            }
        }
        // Флаг гаснет, только когда переразмер действительно доделан: иначе заявка,
        // которую не удалось снять сейчас, осталась бы старого размера навсегда.
        if (complete) {
            resizeRequested = false;
        }
        return cancelled;
    }

    /**
     * Цена нужна как центр нового диапазона, а после рестарта её ещё нет: стрим
     * присылает событие только при сделке, и команда вполне может прийти раньше.
     * Ровно в этом состоянии кнопкой и пользуются, так что спросить цену придётся.
     */
    private void seedLastPrice() {
        if (lastPrice != null) {
            return;
        }
        try {
            LastPrice price = ctx.exchange().marketData().getLastPrice(ctx.execution().instrumentId());
            if (price != null && price.price() != null && price.price().value() != null) {
                lastPrice = price.price().value();
                ctx.observedPrice(lastPrice, ctx.clock().instant());
                return;
            }
        } catch (Exception e) {
            log.warn("Не удалось получить последнюю цену перед ручной перестановкой: {}", e.getMessage());
        }
        // Лучший бид — цена, по которой позиция и будет закрыта. Если и его нет,
        // unwindPrice() бросит понятное исключение, и команда честно не выполнится.
        lastPrice = unwindPrice();
    }

    private void failLowerReplacement(String reason) {
        halted = true;
        buyingStopped = true;
        updateSnapshot();
        ctx.event(BotEventType.RISK_BLOCKED,
                reason + ". Старая сетка сохранена, покупки остановлены.");
    }

    private void stopPermanently(String reason) {
        if (halted) {
            return;
        }
        if (awaitingDownwardReplacement && downwardLossBaseline != null) {
            try {
                realizedDownwardLoss = realizedDownwardLoss.add(
                        downwardLossBaseline.subtract(ctx.realizedPnl()).max(BigDecimal.ZERO));
            } catch (Exception e) {
                ctx.error("Не удалось уточнить уже реализованный убыток при остановке", e);
            }
        }
        halted = true;
        buyingStopped = true;
        awaitingDownwardReplacement = false;
        pendingDownwardRange = null;
        downwardLossBaseline = null;
        forcedReplacement = false;
        try {
            ctx.gateway().cancelAll(ctx.execution());
        } catch (Exception e) {
            ctx.error("Не удалось снять заявки при аварийной остановке", e);
        }
        try {
            persistState();
        } catch (Exception e) {
            ctx.error("Не удалось сохранить причину аварийной остановки", e);
        }
        updateSnapshot();
        try {
            ctx.event(BotEventLevel.ERROR, BotEventType.RANGE_EXIT,
                    reason + ". Бот выключается; оставшуюся позицию проверьте вручную.");
        } catch (Exception e) {
            log.error("Не удалось записать событие аварийной остановки: {}", e.getMessage(), e);
        }
        ctx.requestStop(reason);
    }

    private boolean shouldStopBuying() {
        return blockedByFees || halted || awaitingUpperReplacement
                || awaitingDownwardReplacement || lowerBreakoutPaused || stopScheduled;
    }

    /**
     * Снимает только ОТКРЫВАЮЩИЕ заявки старой сетки; закрывающие обязаны остаться.
     *
     * Ликвидация и продажа пыли исключены явно, а не через «назначение равно GRID»:
     * в шорте закрывающая заявка — покупка, и без этого отбора ликвидационная покупка
     * выглядела бы открытием позиции и снималась бы вместе с сеткой.
     */
    private void cancelOpenBuys() {
        for (BotOrderView order : ctx.gateway().openOrders(ctx.botId())) {
            if (order.purpose() == OrderPurpose.DUST || order.purpose() == OrderPurpose.LIQUIDATION) {
                continue;
            }
            if (roleOf(order) != GridRole.OPEN) {
                continue;
            }
            try {
                ctx.gateway().cancel(ctx.execution(), order.id());
            } catch (Exception e) {
                ctx.error("Не удалось снять покупку " + order.clientOrderId()
                        + " перед перестановкой диапазона", e);
            }
        }
    }

    private boolean tryCompleteUpperReplacement() {
        if (!awaitingUpperReplacement || halted || positionMismatched || lastPrice == null
                || reconciledPosition == null) {
            return false;
        }
        if (!positionIsFlat(reconciledPosition)) {
            reportUpperReplacementStall();
            return false;
        }

        cancelOpenBuys();
        // Пыль исключена намеренно: её продажа переживает перестановку диапазона,
        // потому что и сама пыль копится ЧЕРЕЗ поколения. Ждать её здесь означало
        // бы вернуть тот же вечный тупик, только уже с заявкой на бирже.
        if (!gridOrders().isEmpty()) {
            return false;
        }

        GridRange previous = activeRange;
        GridLadder previousLadder = ladder;
        GridSizing previousSizing = sizing;
        BigDecimal previousBudget = activeBudget;
        GridRange candidate;
        GridLadder candidateLadder;
        GridSizing candidateSizing;
        BigDecimal candidateBudget;
        try {
            VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimateAround(
                    ctx.exchange().marketData(), ctx.execution().instrumentId(), cfg,
                    ctx.constraints().minPriceIncrement(), ctx.clock().instant(), lastPrice,
                    GridRange.Origin.ATR_REPLACED_UP);
            candidate = estimate.range();
            candidateLadder = GridLadder.build(candidate, ctx.constraints().minPriceIncrement());

            // Перестройка сетки — одна из трёх точек, где размер заявки пересчитывается:
            // цены уровней изменились, значит изменилось и то, сколько лотов помещается в бюджет.
            candidateBudget = availableBudget();

            // До этой точки старая сетка и её checkpoint не меняются.
            candidateSizing = GridValidator.validate(cfg, candidate, candidateLadder,
                    ctx.constraints().minPriceIncrement(), activeFees, ctx.constraints().quantityStep(),
                    ctx.execution().maxCapital(), candidateBudget, ctx.carryDailyRate()).sizing();

        } catch (Exception e) {
            failUpperReplacement("Новый диапазон не прошёл проверку: " + e.getMessage());
            return false;
        }

        long previousGeneration = gridGeneration;
        Instant previousReplacementAt = lastReplacementAt;
        activeRange = candidate;
        ladder = candidateLadder;
        sizing = candidateSizing;
        activeBudget = candidateBudget;
        gridGeneration++;
        lastReplacementAt = ctx.clock().instant();
        awaitingUpperReplacement = false;
        upperReplacementStallReported = false;
        buyingStopped = shouldStopBuying();
        halted = false;
        try {
            persistState();
        } catch (Exception e) {
            activeRange = previous;
            ladder = previousLadder;
            sizing = previousSizing;
            activeBudget = previousBudget;
            gridGeneration = previousGeneration;
            lastReplacementAt = previousReplacementAt;
            awaitingUpperReplacement = true;
            failUpperReplacement("Не удалось сохранить новый диапазон: " + e.getMessage());
            return false;
        }

        updateSnapshot();
        String note = "GRID поколение %d: диапазон %s..%s заменён вверх на %s..%s"
                .formatted(gridGeneration,
                        previous.lower().toPlainString(), previous.upper().toPlainString(),
                        activeRange.lower().toPlainString(), activeRange.upper().toPlainString());
        note = withClosedGenerationSummary(note);
        try {
            ctx.ledgerMarker(LedgerEntryType.GRID_REPLACED, note);
        } catch (Exception e) {
            ctx.error("Диапазон заменён, но отметку GRID_REPLACED не удалось записать в книгу", e);
        }
        ctx.event(BotEventType.GRID_REPLACED, note);
        ensureOrders(null);
        return true;
    }

    /**
     * Ожидание, из которого нечему вывести, — это тупик, а не терпение.
     *
     * Пока на бирже висят продажи, ждать правильно: они и закроют позицию. Пусто тоже
     * бывает законно — покупки только что сняты, а встречную продажу поставит
     * {@link #ensureOrders} на этом же проходе. Тупик — это третий случай: заявок нет
     * И позиция не закреплена ни за одним уровнем, то есть ставить продажу попросту
     * не из чего.
     *
     * Проверка на «не из чего» здесь обязательна. Раньше её не было, а первым же
     * вызывающим оказывается ветка подтверждённого пробоя, снимающая покупки строкой
     * выше, — и сообщение вылетало ВСЕГДА, ещё до того, как продажу успевали
     * выставить. Хуже того, флаг после этого взведён, и настоящий тупик того же
     * эпизода прошёл бы молча.
     *
     * Сообщаем один раз за эпизод: тик частый, а журнал событий читают люди.
     */
    private void reportUpperReplacementStall() {
        if (upperReplacementStallReported || !gridOrders().isEmpty()) {
            return;
        }
        BigDecimal uncovered = uncoveredPosition(computeHeldQuantityByLevel());
        if (sellableQuantity(uncovered, lastPrice).signum() <= 0) {
            // Позиция за уровнями закреплена — продажа появится сама.
            return;
        }
        upperReplacementStallReported = true;
        // Это сообщение говорит то же самое, что и общая проверка покрытия, только
        // с последствием. Второй раз повторять его же общими словами незачем.
        uncoveredPositionReported = true;
        ctx.event(BotEventType.RISK_BLOCKED,
                ("Перестановка вверх ждёт закрытия позиции %s, но на %s не отвечает ни один "
                        + "уровень сетки: встречной продажи на этот остаток бот не выставит "
                        + "и ждать будет вечно. Нужна перестройка с фиксацией убытка.")
                        .formatted(plainQuantity(reconciledPosition), plainQuantity(uncovered)));
    }

    private void failUpperReplacement(String reason) {
        halted = true;
        buyingStopped = true;
        updateSnapshot();
        ctx.event(BotEventType.RISK_BLOCKED,
                reason + ". Старая сетка сохранена, покупки остановлены.");
    }

    /**
     * Переводит учёт поколений на действующий диапазон.
     *
     * Статистика не должна мешать торговле: сорвавшаяся запись отчёта — повод для
     * строки в журнале, но не для отката перестановки, которая уже случилась на бирже.
     */
    private Optional<GridGenerationDto> rollGeneration() {
        if (activeRange == null) {
            return Optional.empty();
        }
        try {
            Optional<GridGenerationDto> closed = ctx.rollGridGeneration(
                    gridGeneration, activeRange.lower(), activeRange.upper(),
                    activeRange.levels(), activeRange.origin().name(), activeRange.since(),
                    direction.name(), cfg.marginEnabled());
            return closed == null ? Optional.empty() : closed;
        } catch (Exception e) {
            ctx.error("Не удалось обновить статистику поколений сетки", e);
            return Optional.empty();
        }
    }

    /**
     * Дописывает к сообщению о перестановке итог закрытого поколения.
     *
     * Именно этот текст уходит в Telegram, и смысл в том, чтобы вопрос «а стоила ли
     * прошлая сетка того» закрывался прямо в уведомлении, без похода в интерфейс.
     */
    private String withClosedGenerationSummary(String note) {
        return rollGeneration()
                .map(closed -> note + "\n" + closed.humanSummary())
                .orElse(note);
    }

    private void persistState() {
        ctx.saveState(new GridStrategyState(
                activeRange, gridGeneration, awaitingUpperReplacement, lastReplacementAt,
                awaitingDownwardReplacement, pendingDownwardRange, downwardReplacements,
                realizedDownwardLoss, downwardLossBaseline, forcedReplacement, stopScheduled,
                hedgeEpisode, hedgeEpisodesUsed, direction));
    }

    /**
     * Выход цены за нижнюю границу — главный риск конструкции: без этой ветки
     * сетка неограниченно усредняется в падающий рынок.
     *
     * @return true, если бот дальше действовать не должен
     */
    private boolean checkRangeExit() {
        if (cfg.onRangeExit() == GridConfig.RangeExitAction.REPLACE_LOWER) {
            return false;
        }
        if (!direction.beyondAdverse(lastPrice, direction.adverseBound(activeRange))) {
            return false;
        }
        if (buyingStopped) {
            return cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP;
        }

        buyingStopped = true;
        updateSnapshot();

        if (cfg.onRangeExit() == GridConfig.RangeExitAction.CANCEL_AND_STOP) {
            int cancelled = ctx.gateway().cancelAll(ctx.execution());
            ctx.event(BotEventType.RANGE_EXIT,
                    "Цена %s ушла ниже диапазона (%s). Снято заявок: %d, торговля остановлена."
                            .formatted(lastPrice.toPlainString(), activeRange.lower().toPlainString(), cancelled));
            return true;
        }

        ctx.event(BotEventType.RANGE_EXIT,
                "Цена %s ушла ниже диапазона (%s). Покупки прекращены, продажи продолжаются."
                        .formatted(lastPrice.toPlainString(), activeRange.lower().toPlainString()));
        return false;
    }

    // ==============================
    // HELPERS
    // ==============================

    private boolean isReady() {
        return !stopped && ctx != null && ladder != null && cfg.enabled();
    }

    private FeeInfo resolveFeeInfo() {
        try {
            var fee = ctx.exchange().fees()
                    .getFeeInfo(ctx.execution().accountId(), ctx.execution().instrumentId());
            if (fee != null) {
                return fee;
            }
        } catch (Exception e) {
            log.warn("Не удалось получить ставку комиссии: {}", e.getMessage());
        }
        // Молча считать комиссию нулевой нельзя — это разрешило бы заведомо убыточную сетку.
        throw new IllegalStateException(
                "Не удалось определить ставку комиссии. Задайте её в настройках подключения.");
    }

    /**
     * Комиссия может меняться без рестарта бота: на криптобиржах тариф зависит от
     * объёма торгов, VIP-уровня и пары. Если текущий тариф сделал сетку убыточной,
     * новые покупки прекращаются; уже купленное продолжает продаваться.
     */
    private void refreshFeesIfNeeded() {
        if (nextFeeRefreshAt == null || ctx.clock().instant().isBefore(nextFeeRefreshAt)) {
            return;
        }
        nextFeeRefreshAt = ctx.clock().instant().plusSeconds(cfg.feeRefreshSeconds());

        FeeInfo refreshed;
        try {
            refreshed = resolveFeeInfo();
            // revalidate, а НЕ validate: размер заявки здесь пересчитывать нельзя.
            // Иначе у бота с реинвестированием прибыли объём менялся бы посреди жизни
            // сетки — между покупкой и её ещё не выставленной встречной продажей.
            GridValidator.revalidate(cfg, activeRange, ladder, ctx.constraints().minPriceIncrement(),
                    refreshed, ctx.execution().maxCapital(), sizing, ctx.carryDailyRate());
        } catch (Exception e) {
            if (!blockedByFees) {
                blockedByFees = true;
                buyingStopped = true;
                updateSnapshot();
                ctx.event(BotEventType.RISK_BLOCKED,
                        "Комиссии не удалось обновить или сетка перестала окупать оборот: "
                                + e.getMessage() + ". Новые покупки остановлены.");
            }
            return;
        }

        activeFees = refreshed;
        if (blockedByFees) {
            blockedByFees = false;
            buyingStopped = shouldStopBuying();
            updateSnapshot();
            ctx.event(BotEventType.HOUSEKEEPING,
                    "Комиссия обновлена, экономика сетки снова проходит проверку. Покупки возобновлены.");
        }
    }

    /** Для UI: уровни сетки с их ценами. */
    public List<BigDecimal> ladderPrices() {
        return ladder == null ? List.of() : ladder.prices();
    }

    @Override
    public Optional<StrategySnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    private void updateSnapshot() {
        if (activeRange == null || ladder == null) {
            snapshot = null;
            return;
        }
        snapshot = new StrategySnapshot(
                activeRange.lower(), activeRange.upper(), ladder.prices(),
                activeRange.origin().name(), activeRange.since(), gridGeneration,
                buyingStopped, awaitingUpperReplacement || awaitingDownwardReplacement,
                awaitingDownwardReplacement ? "DOWN" : awaitingUpperReplacement ? "UP" : null,
                downwardReplacements, realizedDownwardLoss, halted, stopScheduled,
                sizing == null ? null : sizing.quantityByLevel(),
                sizing == null ? null : sizing.mode().name(),
                sizing == null ? null : sizing.workingBudget(),
                sizing == null ? null : sizing.worstCaseNotional());
    }

    /** Человекочитаемое описание того, как посчитан размер заявки. */
    private String sizingSummary() {
        if (sizing == null) {
            return "размер заявки не рассчитан";
        }
        return switch (sizing.mode()) {
            case FIXED_QUANTITY -> "по %s".formatted(plainQuantity(sizing.quantityAt(0)));
            case UNIFORM -> "по %s (бюджет %s, задействовано %s)"
                    .formatted(plainQuantity(sizing.quantityAt(0)),
                            plain(sizing.workingBudget()), plain(sizing.worstCaseNotional()));
            case PER_LEVEL -> "по %s..%s по уровням (бюджет %s, задействовано %s, остаток %s)"
                    .formatted(plainQuantity(sizing.minQuantity()), plainQuantity(sizing.maxQuantity()),
                            plain(sizing.workingBudget()), plain(sizing.worstCaseNotional()),
                            plain(sizing.budgetLeftover()));
        };
    }

    /** Деньги: две значащие после запятой. */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * Количество: без округления и без экспоненты. Округлять здесь нельзя —
     * 0.000001 BTC превратилось бы в ноль, то есть сообщение соврало бы о заявке.
     */
    private static String plainQuantity(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private record RangeResolution(
            GridRange range,
            long generation,
            BigDecimal referencePrice,
            boolean persist,
            GridStrategyState state
    ) {
    }
}
