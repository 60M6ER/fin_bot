package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.accounting.BotValuationService;
import ru.larionov.backend.accounting.GridGenerationService;
import ru.larionov.backend.dto.*;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.CommandRequest;
import ru.larionov.backend.strategy.StrategyCommand;
import ru.larionov.backend.strategy.grid.GridConfig;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.execution.RiskGuard;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BotService {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN);

    private final BotRepository botRepo;
    private final ExchangeConnectionRepository connectionRepo;
    private final BotOrderRepository orderRepo;
    private final BotRuntimeService botRuntimeService;
    private final ExchangeRuntimeService exchangeRuntimeService;
    private final BotEventService eventService;
    private final AccountingService accountingService;
    private final GridGenerationService gridGenerationService;
    private final BotValuationService valuationService;
    private final RiskGuard riskGuard;
    private final ObjectMapper objectMapper;

    public List<BotListItemDto> list() {
        List<BotEntity> bots = botRepo.findAll();

        // Имена подключений одним запросом, чтобы не ходить в БД в цикле.
        Map<UUID, String> connectionNames = new HashMap<>();
        for (ExchangeConnectionEntity c : connectionRepo.findAll()) {
            connectionNames.put(c.getId(), c.getName());
        }

        return bots.stream()
                .map(b -> toListItem(b, connectionNames.get(b.getExchangeConnectionId())))
                .toList();
    }

    public BotDetailDto get(UUID id) {
        return toDetail(requireBot(id));
    }

    /** Типы стратегий для выпадающего списка в UI. */
    public List<StrategyType> getStrategyTypes() {
        return List.of(StrategyType.values());
    }

    /** Что бот делает прямо сейчас: позиция, активные заявки, длина очереди событий. */
    public BotTradingStateDto tradingState(UUID id) {
        BotEntity bot = requireBot(id);

        List<BotOrderEntity> open = orderRepo.findAllByBotIdAndStatusIn(id, OPEN_STATUSES);
        List<BotOrderEntity> recent = orderRepo.findTop200ByBotIdOrderByCreatedAtDesc(id);

        boolean dryRun = open.stream().anyMatch(BotOrderEntity::isDryRun)
                || recent.stream().findFirst().map(BotOrderEntity::isDryRun).orElse(false);

        BigDecimal reserved = BigDecimal.ZERO;
        for (BotOrderEntity o : open) {
            if (o.getSide() == OrderSide.BUY && o.getLimitPrice() != null) {
                reserved = reserved.add(o.getLimitPrice().multiply(o.remainingQuantity()));
            }
        }

        return new BotTradingStateDto(
                botRuntimeService.isRunning(id),
                dryRun,
                orderRepo.sumPositionQuantity(id, dryRun),
                reserved,
                open.size(),
                botRuntimeService.queueSize(id),
                botRuntimeService.strategySnapshot(id).orElse(null),
                // Только активные заявки. Раньше сюда шли последние 200 ордеров ЛЮБОГО
                // статуса, и виджет состояния показывал давно исполненные и отменённые
                // вперемешку с живыми — уровень выглядел занятым ордером, которого
                // на бирже уже нет. История и так есть ниже: книга сделок и журнал событий.
                open.stream()
                        .sorted(Comparator.comparing(BotOrderEntity::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .map(BotOrderView::of)
                        .toList()
        );
    }

    public List<BotEventDto> events(UUID id, int limit) {
        requireBot(id);
        return eventService.recent(id, limit).stream().map(BotEventDto::of).toList();
    }

    public BotValuationDto accounting(UUID id, Boolean dryRun) {
        return valuationService.accounting(requireBot(id), dryRun);
    }

    public List<MoneyLedgerDto> ledger(UUID id, Boolean dryRun) {
        requireBot(id);
        return accountingService.ledger(id, resolveDryRun(id, dryRun));
    }

    /** Успешность каждого диапазона по отдельности: от свежего поколения к старому. */
    public List<GridGenerationDto> generations(UUID id, Boolean dryRun) {
        requireBot(id);
        return gridGenerationService.list(id, resolveDryRun(id, dryRun));
    }

    /** Боты конкретного подключения — для экрана биржи: видно, что остановит её выключение. */
    public List<BotListItemDto> listByConnection(UUID connectionId) {
        String name = connectionRepo.findById(connectionId)
                .map(ExchangeConnectionEntity::getName)
                .orElse(null);

        return botRepo.findAllByExchangeConnectionIdOrderByNameAsc(connectionId).stream()
                .map(b -> toListItem(b, name))
                .toList();
    }

    @Transactional
    public UUID create(BotCreateRequest req) {
        BotEntity b = BotEntity.builder()
                .name(requireName(req.name()))
                .strategyType(requireStrategyType(req.strategyType()))
                .exchangeConnectionId(requireExistingConnection(req.exchangeConnectionId()))
                .strategyConfig(normalizeJson(req.strategyConfig()))
                .active(false)
                .build();

        botRepo.save(b);
        return b.getId();
    }

    @Transactional
    public void update(UUID id, BotUpdateRequest req) {
        BotEntity b = requireBot(id);
        requireStopped(id, "изменить настройки");

        b.setName(requireName(req.name()));
        b.setStrategyType(requireStrategyType(req.strategyType()));
        b.setExchangeConnectionId(requireExistingConnection(req.exchangeConnectionId()));
        b.setStrategyConfig(normalizeJson(req.strategyConfig()));

        botRepo.save(b);
    }

    /**
     * Бюджет на лету — единственная настройка, которую можно менять под работающим ботом.
     *
     * Общая форма настроек требует остановки, а остановка снимает ВСЕ заявки, включая
     * встречные продажи незакрытых циклов. Для доливки денег или их вывода это слишком
     * дорого: диапазон, поколение и открытые циклы менять не нужно — нужен только другой
     * размер будущих покупок.
     *
     * Порядок намеренный: сначала проверки, потом запись в конфигурацию, и только потом
     * команда живому боту. Запись обязательна — без неё первый же рестарт вернул бы
     * прежний бюджет, и бот бы тихо торговал не тем размером, который показан оператору.
     *
     * Здесь проверяется только то, что видно снаружи торгового цикла. Хватает ли бюджета
     * на шаг количества на каждом уровне и окупает ли шаг комиссию, знает лишь сама
     * стратегия — у неё действующая лесенка. Её отказ придёт событием бота.
     */
    @Transactional
    public BotBudgetChangeDto updateBudget(UUID id, BotBudgetUpdateRequest req) {
        BotEntity b = requireBot(id);
        if (b.getStrategyType() != StrategyType.GRID) {
            throw new IllegalStateException("Бюджет есть только у GRID-бота.");
        }
        GridConfig cfg = readConfig(b, GridConfig.class);
        BigDecimal budget = resolveBudget(cfg, req);
        if (!cfg.budgetSized()) {
            throw new IllegalStateException(
                    "У бота фиксированный размер заявки — бюджет в расчёте размеров не участвует. "
                            + "Смените режим размера заявки в настройках остановленного бота.");
        }
        BotRuntimeConfig runtime = readConfig(b, BotRuntimeConfig.class);
        BigDecimal maxCapital = req.maxCapital() != null ? req.maxCapital() : runtime.maxCapital();

        BigDecimal realizedPnl = nvl(accountingService.summary(id, runtime.dryRun()).realizedPnl());
        BigDecimal working = cfg.withBudget(budget).workingBudget(() -> realizedPnl);

        RiskGuard.CapitalUsage usage = riskGuard.capitalUsage(id, runtime.dryRun());
        BigDecimal positionCost = nvl(usage.inventory().costBasisOpen());

        // Пол — себестоимость уже открытой позиции: снятые покупки деньги вернут,
        // а купленное обратно не раскупить. Бюджет ниже нельзя не из осторожности —
        // при нём сетка не сможет обслуживать собственную позицию.
        if (working.compareTo(positionCost) < 0) {
            throw new IllegalStateException(
                    ("Бюджет %s меньше стоимости уже открытой позиции (%s). Вывести можно только "
                            + "то, что ещё не вложено; чтобы освободить остальное, позицию надо "
                            + "сначала продать — плановой остановкой или перестройкой с фиксацией "
                            + "убытка.").formatted(plain(working), plain(positionCost)));
        }
        if (maxCapital != null && maxCapital.signum() > 0 && working.compareTo(maxCapital) > 0) {
            throw new IllegalStateException(
                    ("Бюджет %s выше потолка задействованного капитала (%s): сетка не смогла бы "
                            + "выкупить собственные уровни. Поднимите потолок вместе с бюджетом.")
                            .formatted(plain(working), plain(maxCapital)));
        }

        b.setStrategyConfig(writeBudget(b.getStrategyConfig(), budget, req.maxCapital()));
        botRepo.save(b);

        boolean running = botRuntimeService.isRunning(id);
        if (running) {
            botRuntimeService.command(id,
                    new CommandRequest(StrategyCommand.SET_BUDGET, budget));
        }

        BigDecimal committed = nvl(usage.amount());
        return new BotBudgetChangeDto(cfg.budget(), budget, working, committed,
                working.subtract(committed), positionCost, running);
    }

    /**
     * Во что превращается запрос: целевой бюджет.
     *
     * Прибавку складываем ЗДЕСЬ, а не в интерфейсе. Снаружи виден рабочий бюджет —
     * «бюджет плюс реализованная прибыль» у ботов с реинвестированием, — и прибавка
     * к нему тихо превратила бы уже заработанное в базовый бюджет: прибыль перестала
     * бы отделяться от вложенного, а при выводе прибыли ещё и вывелась бы дважды.
     * Складывать можно только с той суммой, которая записана в конфигурации.
     */
    private BigDecimal resolveBudget(GridConfig cfg, BotBudgetUpdateRequest req) {
        if (req.budget() != null && req.addToBudget() != null) {
            throw new IllegalArgumentException(
                    "Укажите либо новый бюджет целиком, либо прибавку к нему, но не оба сразу");
        }

        BigDecimal target;
        if (req.addToBudget() != null) {
            if (req.addToBudget().signum() == 0) {
                throw new IllegalArgumentException("Прибавка к бюджету не может быть нулевой");
            }
            target = nvl(cfg.budget()).add(req.addToBudget());
            if (target.signum() <= 0) {
                throw new IllegalStateException(
                        ("Вывести %s нельзя: в бюджете всего %s.")
                                .formatted(plain(req.addToBudget().negate()), plain(cfg.budget())));
            }
        } else {
            target = req.budget();
        }

        if (target == null || target.signum() <= 0) {
            throw new IllegalArgumentException("Бюджет должен быть больше нуля");
        }
        return target;
    }

    /**
     * Правим ДЕРЕВО, а не пересобираем конфигурацию из разобранного объекта: в том же
     * JSON живут поля движка и всё, о чём эта версия кода ещё не знает. Пересборка
     * молча стёрла бы их.
     */
    private String writeBudget(String json, BigDecimal budget, BigDecimal maxCapital) {
        try {
            var node = (tools.jackson.databind.node.ObjectNode) objectMapper.readTree(
                    json == null || json.isBlank() ? "{}" : json);
            node.put("budget", budget);
            if (maxCapital != null) {
                node.put("maxCapital", maxCapital);
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось записать бюджет в конфигурацию бота: " + e.getMessage(), e);
        }
    }

    private <T> T readConfig(BotEntity bot, Class<T> type) {
        try {
            String json = bot.getStrategyConfig();
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось прочитать конфигурацию бота: " + e.getMessage(), e);
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    @Transactional
    public void delete(UUID id) {
        if (!botRepo.existsById(id)) {
            throw new NotFoundException("Bot not found: " + id);
        }
        requireStopped(id, "удалить бота");

        // Живые заявки на бирже переживут удаление бота, и снимать их станет некому.
        if (orderRepo.existsByBotIdAndStatusIn(id, OPEN_STATUSES)) {
            throw new IllegalStateException(
                    "У бота есть незакрытые ордера. Запустите его и дождитесь закрытия "
                            + "или снимите заявки вручную в приложении брокера.");
        }

        // Журнал событий — лог, удаляем вместе с ботом.
        // Журнал ордеров (bot_order) остаётся намеренно: это финансовая запись.
        eventService.deleteAllForBot(id);
        gridGenerationService.deleteAllForBot(id);
        botRepo.deleteById(id);
        valuationService.forget(id);
    }

    // ==============================
    // VALIDATION
    // ==============================

    private BotEntity requireBot(UUID id) {
        return botRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Bot not found: " + id));
    }

    /**
     * Работающего бота нельзя перенастраивать или удалять: у него уже выставлены
     * ордера, привязанные к текущей стратегии и подключению.
     */
    private void requireStopped(UUID botId, String action) {
        if (botRuntimeService.isRunning(botId)) {
            throw new IllegalStateException("Нельзя " + action + ": бот запущен. Сначала остановите его.");
        }
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return raw.trim();
    }

    private StrategyType requireStrategyType(StrategyType type) {
        if (type == null) {
            throw new IllegalArgumentException("strategyType is required");
        }
        return type;
    }

    private UUID requireExistingConnection(UUID connectionId) {
        if (connectionId == null) {
            throw new IllegalArgumentException("exchangeConnectionId is required");
        }
        if (!connectionRepo.existsById(connectionId)) {
            throw new NotFoundException("Exchange connection not found: " + connectionId);
        }
        return connectionId;
    }

    /**
     * Колонка strategy_config имеет тип jsonb — невалидный JSON Postgres не примет,
     * поэтому проверяем здесь и отдаём внятную ошибку вместо ошибки драйвера.
     */
    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) {
            return "{}";
        }
        String trimmed = json.trim();
        try {
            objectMapper.readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("strategyConfig is not valid JSON: " + e.getMessage(), e);
        }
        return trimmed;
    }

    // ==============================
    // MAPPING
    // ==============================

    private BotListItemDto toListItem(BotEntity b, String connectionName) {
        return new BotListItemDto(
                b.getId(),
                b.getName(),
                b.getStrategyType(),
                b.getExchangeConnectionId(),
                connectionName,
                b.isActive(),
                runtimeOrDefault(b.getId()),
                valuationOrEmpty(b)
        );
    }

    /**
     * Сбой оценки не должен ронять весь список: остальные боты в нём ни при чём.
     */
    private BotValuationDto valuationOrEmpty(BotEntity b) {
        try {
            return valuationService.valuation(b);
        } catch (Exception e) {
            log.warn("Не удалось оценить бота {}: {}", b.getId(), e.getMessage());
            return BotValuationDto.empty(false);
        }
    }

    private BotDetailDto toDetail(BotEntity b) {
        ExchangeConnectionEntity conn = connectionRepo.findById(b.getExchangeConnectionId()).orElse(null);

        return new BotDetailDto(
                b.getId(),
                b.getName(),
                b.getStrategyType(),
                b.getExchangeConnectionId(),
                conn != null ? conn.getName() : null,
                exchangeRuntimeService.runtimeInfo(b.getExchangeConnectionId()).state(),
                b.getStrategyConfig(),
                b.isActive(),
                runtimeOrDefault(b.getId()),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private RuntimeInfo runtimeOrDefault(UUID id) {
        RuntimeInfo info = botRuntimeService.getRuntime(id);
        return info != null ? info : new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now());
    }

    private boolean resolveDryRun(UUID id, Boolean dryRun) {
        if (dryRun != null) {
            return dryRun;
        }
        List<BotOrderEntity> recent = orderRepo.findTop200ByBotIdOrderByCreatedAtDesc(id);
        return recent.stream().findFirst().map(BotOrderEntity::isDryRun).orElse(false);
    }
}
