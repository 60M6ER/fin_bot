package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotEventEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.repository.BotEventRepository;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Журнал событий бота: одна запись питает консоль, Telegram и ленту в UI.
 *
 * Разделение ответственности намеренное:
 * <ul>
 *   <li>в журнал и консоль попадает ВСЁ — без этого разбор происшествия невозможен;</li>
 *   <li>в Telegram уходит только то, что меняет деньги или требует внимания,
 *       да и то через ограничитель частоты.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotEventService {

    private final BotEventRepository repo;
    private final BotRepository botRepo;
    private final TelegramNotifyService notifyService;
    private final NotificationThrottle throttle;
    private final ObjectMapper objectMapper;

    /** Имена ботов для читаемых уведомлений: UUID в телефоне бесполезен. */
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    /**
     * Запись события не должна ронять торговую операцию, внутри которой она случилась,
     * поэтому идёт в отдельной транзакции и глотает свои ошибки: потерять строку лога
     * неприятно, но потерять из-за неё ордер — хуже.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void emit(UUID botId, BotEventLevel level, BotEventType type, String message, Map<String, String> payload) {
        try {
            repo.save(BotEventEntity.builder()
                    .botId(botId)
                    .level(level)
                    .type(type)
                    .message(message)
                    .payload(payload == null || payload.isEmpty() ? "{}" : objectMapper.writeValueAsString(payload))
                    .build());
        } catch (Exception e) {
            log.warn("Не удалось сохранить событие бота {}: {}", botId, e.getMessage());
        }

        logToConsole(botId, level, type, message);
        maybeNotify(botId, level, type, message);
    }

    public void emit(UUID botId, BotEventLevel level, BotEventType type, String message) {
        emit(botId, level, type, message, Map.of());
    }

    private void maybeNotify(UUID botId, BotEventLevel level, BotEventType type, String message) {
        if (!type.isNotifiable()) {
            return;
        }

        // Ключ дедупликации — тип плюс текст: повторяющаяся из тика в тик ошибка
        // не должна уходить в Telegram десятки раз подряд.
        String dedupKey = type.name() + '|' + (message == null ? "" : message);
        NotificationThrottle.Decision decision = throttle.decide(botId, dedupKey);

        if (decision != NotificationThrottle.Decision.SEND) {
            log.debug("Уведомление подавлено ({}) для бота {}: {}", decision, botId, type);
            return;
        }

        notifyService.broadcast("%s %s\n\nБот: %s\n%s".formatted(
                icon(level), humanType(type), botName(botId), message == null ? "" : message));
    }

    /**
     * Сводка о скрытых уведомлениях. Без неё тишина в Telegram была бы неотличима
     * от отсутствия событий — а это разные вещи.
     */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void flushSuppressedSummaries() {
        for (UUID botId : throttle.knownBots()) {
            try {
                String summary = throttle.drainSummary(botId);
                if (summary != null) {
                    notifyService.broadcast("Бот: %s\n%s".formatted(botName(botId), summary));
                }
            } catch (Exception e) {
                log.warn("Не удалось отправить сводку по боту {}: {}", botId, e.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    public List<BotEventEntity> recent(UUID botId, int limit) {
        return repo.findAllByBotIdOrderByTsDesc(botId, PageRequest.of(0, Math.min(Math.max(limit, 1), 500)));
    }

    @Transactional
    public void deleteAllForBot(UUID botId) {
        repo.deleteAllByBotId(botId);
        nameCache.remove(botId);
        throttle.forget(botId);
    }

    private String botName(UUID botId) {
        return nameCache.computeIfAbsent(botId, id ->
                botRepo.findById(id).map(b -> b.getName()).orElse(id.toString()));
    }

    private void logToConsole(UUID botId, BotEventLevel level, BotEventType type, String message) {
        switch (level) {
            case ERROR -> log.error("[bot {}] {}: {}", botId, type, message);
            case WARN -> log.warn("[bot {}] {}: {}", botId, type, message);
            case DEBUG -> log.debug("[bot {}] {}: {}", botId, type, message);
            default -> log.info("[bot {}] {}: {}", botId, type, message);
        }
    }

    private static String icon(BotEventLevel level) {
        return switch (level) {
            case ERROR -> "❌";
            case WARN -> "⚠️";
            default -> "ℹ️";
        };
    }

    private static String humanType(BotEventType type) {
        return switch (type) {
            case BOT_STARTED -> "Бот запущен";
            case BOT_STOPPED -> "Бот остановлен";
            case ORDER_PLACED -> "Заявка выставлена";
            case ORDER_FILLED -> "Заявка исполнена";
            case ORDER_CANCELLED -> "Заявка снята";
            case ORDER_REJECTED -> "Заявка отклонена";
            case CYCLE_CLOSED -> "Цикл закрыт";
            case RANGE_EXIT -> "Выход из диапазона";
            case RISK_BLOCKED -> "Сработал лимит";
            case STREAM_RECONNECTED -> "Переподключение стрима";
            case RECONCILED -> "Сверка с биржей";
            case ERROR -> "Ошибка";
            case HOUSEKEEPING -> "Событие";
        };
    }
}
