package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
                reserved = reserved.add(o.getLimitPrice().multiply(BigDecimal.valueOf(o.remainingLots())));
            }
        }

        return new BotTradingStateDto(
                botRuntimeService.isRunning(id),
                dryRun,
                orderRepo.sumPositionLots(id, dryRun),
                reserved,
                open.size(),
                botRuntimeService.queueSize(id),
                recent.stream().limit(50).map(BotOrderView::of).toList()
        );
    }

    public List<BotEventDto> events(UUID id, int limit) {
        requireBot(id);
        return eventService.recent(id, limit).stream().map(BotEventDto::of).toList();
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
        botRepo.deleteById(id);
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
                runtimeOrDefault(b.getId())
        );
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
}
