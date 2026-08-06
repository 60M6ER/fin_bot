package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.accounting.BotValuationService;
import ru.larionov.backend.dto.BotValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.BotEventEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.repository.BotEventRepository;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
 *   <li>в Telegram уходит только то, что меняет деньги или требует внимания —
 *       целиком, без подавления повторов: склейку пачки в одно сообщение делает
 *       {@code NotificationAggregator}, и терять содержимое ради тишины не нужно.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotEventService {

    private final BotEventRepository repo;
    private final BotRepository botRepo;
    private final TelegramNotifyService notifyService;
    /*
     * Через ObjectProvider, чтобы разорвать цикл зависимостей: оценка бота тянет
     * свободные деньги счёта, те — рантайм подключений, а тот в итоге возвращается
     * сюда, в журнал событий.
     */
    private final ObjectProvider<BotValuationService> valuationService;
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

    /**
     * Уведомления не фильтруются: что произошло, то и уходит.
     *
     * Раньше здесь подавлялись повторы одного и того же текста в окне пяти минут,
     * а вместо них раз в минуту приходила строка «Скрыто повторов: N». На практике
     * это оказалось худшим из вариантов: сама по себе такая строка не говорит ничего,
     * а за ней пряталось именно то, что нужно было видеть — залипшая проблема,
     * повторяющаяся из тика в тик.
     *
     * Ради чего подавление вводилось — чтобы телефон не разрывало, — уже решает
     * {@code NotificationAggregator}: он ждёт тишины и склеивает пачку в одно
     * сообщение, ничего не выбрасывая. Два механизма на одну задачу были лишними,
     * и лишним оказался именно тот, который терял содержимое.
     */
    private void maybeNotify(UUID botId, BotEventLevel level, BotEventType type, String message) {
        if (!type.isNotifiable()) {
            return;
        }

        String block = "%s %s\nБот: %s\n%s".formatted(
                icon(level), humanType(type), botHeadline(botId), message == null ? "" : message);

        // Ошибка уходит отдельным сообщением и сразу: склеенная с housekeeping-строками,
        // она теряется среди них, а увидеть её надо первой.
        if (level == BotEventLevel.ERROR || type == BotEventType.ERROR) {
            notifyService.broadcastIsolated(block);
        } else {
            notifyService.broadcast(block);
        }
    }

    /**
     * Имя бота вместе с его деньгами: «MAGN GRID · 10 000 ₽ · +12,34 ₽».
     *
     * Смысл в том, чтобы уведомление отвечало на вопрос «и что теперь» без похода
     * в интерфейс: сколько у бота сейчас и сколько он на этом заработал.
     */
    private String botHeadline(UUID botId) {
        String name = botName(botId);
        try {
            BotEntity bot = botRepo.findById(botId).orElse(null);
            if (bot == null) {
                return name;
            }
            BotValuationDto v = valuationService.getObject().valuation(bot);
            String currency = v.currency();

            // P/L с учётом позиции, если цена известна; иначе только реализованный.
            BigDecimal pnl = v.totalPnl() != null ? v.totalPnl() : v.realizedPnl();

            StringBuilder sb = new StringBuilder(name);
            if (v.equity() != null) {
                sb.append(" · ").append(MoneyFormat.money(v.equity(), currency));
            }
            if (pnl != null) {
                sb.append(" · ").append(MoneyFormat.signed(pnl, currency));
                if (v.totalPnl() == null) {
                    // Честно помечаем неполноту: открытые лоты в этот P/L не вошли.
                    sb.append(" (реализ.)");
                }
            }
            return sb.toString();

        } catch (Exception e) {
            // Уведомление важнее украшений: без денег, но дойдёт.
            log.debug("Не удалось добавить деньги бота {} в уведомление: {}", botId, e.getMessage());
            return name;
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
            case GRID_REPLACED -> "Диапазон переставлен";
            case RISK_BLOCKED -> "Сработал лимит";
            case STREAM_RECONNECTED -> "Переподключение стрима";
            case RECONCILED -> "Сверка с биржей";
            case ERROR -> "Ошибка";
            case HOUSEKEEPING -> "Событие";
        };
    }
}
