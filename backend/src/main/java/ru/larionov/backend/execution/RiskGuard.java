package ru.larionov.backend.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.accounting.Inventory;
import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.enums.OrderStatus;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.TradingSwitch;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Проверка лимитов перед постановкой ордера.
 *
 * Живёт внутри гейтвея, а не в стратегии: стратегия не должна иметь возможности
 * обойти лимиты — ни по невнимательности, ни из-за бага в своей логике.
 *
 * Считает по журналу, а не по памяти, поэтому лимиты переживают рестарт: бот,
 * перезапущенный после того, как выбрал дневной лимит ордеров, не начнёт с нуля.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskGuard {

    private static final List<OrderStatus> OPEN_STATUSES =
            List.of(OrderStatus.PENDING, OrderStatus.NEW, OrderStatus.PARTIALLY_FILLED, OrderStatus.UNKNOWN);

    private final BotOrderRepository orderRepo;
    private final TradingSwitch tradingSwitch;
    private final AccountingService accounting;

    /**
     * Отметки времени последних постановок для минутного лимита.
     * Именно этот лимит — новый по сравнению с поллинговой схемой: раньше частоту
     * действий естественно ограничивал период опроса, на стриме такого ограничителя нет,
     * и баг в реакции на события способен высадить сотни ордеров за минуту.
     */
    private final Map<UUID, ConcurrentLinkedDeque<Instant>> recentPlacements = new ConcurrentHashMap<>();

    public void check(BotExecutionContext ctx, PlaceIntent intent) {
        // Глобальный стоп-кран проверяем первым: он должен действовать немедленно,
        // не дожидаясь остановки ботов.
        if (!ctx.dryRun() && !tradingSwitch.isEnabled()) {
            throw new RiskRejectedException(
                    "Торговля остановлена глобальным стоп-краном (Настройки → Торговля).");
        }

        checkOrdersPerMinute(ctx);
        checkOrdersPerDay(ctx);
        checkLevelNotTaken(ctx, intent);
        checkNoShort(ctx, intent);
        checkPosition(ctx, intent);
        checkCapital(ctx, intent);
    }

    /**
     * На одном уровне сетки не может висеть двух одинаковых заявок.
     *
     * Это инвариант, а не настройка: уровень — это одна цена и один незакрытый цикл,
     * и вторая заявка на нём удваивает вложенные деньги, а встречная продажа потом
     * закрывает только половину.
     *
     * Проверка живёт здесь по той же причине, что и запрет шорта: она обязана пережить
     * ЛЮБУЮ ошибку стратегии. 09.08.2026 бот на SOL/USDT выставил на нулевом уровне
     * две покупки подряд — стратегия сочла уровень свободным дважды, и остановить её
     * было нечему. Что именно скрыло первую заявку от второго прохода, по журналу
     * восстановить не удалось, поэтому дубль сделан структурно невозможным и громким:
     * причина всплывёт в отказе, а не в деньгах.
     */
    private void checkLevelNotTaken(BotExecutionContext ctx, PlaceIntent intent) {
        if (intent.gridLevel() == null || intent.side() != OrderSide.BUY) {
            // Только покупки. Заявка вне сетки (ликвидация, продажа пыли) уровня не
            // имеет вовсе, а ПРОДАЖ на одном уровне законно бывает несколько: уровень
            // мог набираться частями, и каждая закрывается своей встречной заявкой.
            // Запрет на них означал бы, что вторая часть уровня не продастся никогда.
            return;
        }
        for (BotOrderEntity o : orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES)) {
            if (o.isDryRun() != ctx.dryRun()
                    || o.getSide() != intent.side()
                    || !Objects.equals(o.getGridLevel(), intent.gridLevel())) {
                continue;
            }
            throw new RiskRejectedException(
                    ("На уровне %d уже висит покупка (%s, осталось %s). Вторая удвоила бы "
                            + "вложенное в уровень, а встречная продажа закрыла бы половину.")
                            .formatted(intent.gridLevel(), o.getStatus(),
                                    plain(o.remainingQuantity())));
        }
    }

    /**
     * Продать можно только то, что действительно куплено.
     *
     * Это не настраиваемый лимит, а инвариант: сетка объявлена лонговой, а на счёте
     * без маржи короткая позиция и не откроется — брокер просто отклонит заявку,
     * зато с маржой риск перестанет быть ограниченным.
     *
     * Проверка живёт здесь, а не в стратегии, именно потому, что должна пережить
     * ЛЮБУЮ ошибку стратегии. Реальный случай: стратегия недосчитала уже выставленные
     * продажи и раз за разом добавляла новые — единственным, что это остановило,
     * оказался лимит частоты, то есть случайность.
     *
     * Считаем по журналу: он достоверен, пока сверка не сообщила о расхождении,
     * а при расхождении бот и так обязан молчать.
     */
    private void checkNoShort(BotExecutionContext ctx, PlaceIntent intent) {
        if (intent.side() != OrderSide.SELL) {
            return;
        }

        BigDecimal position = nvl(orderRepo.sumPositionQuantity(ctx.botId(), ctx.dryRun()));
        BigDecimal alreadyOffered = openQuantity(ctx, OrderSide.SELL);
        BigDecimal projected = alreadyOffered.add(intent.quantity());

        if (projected.compareTo(position) > 0) {
            throw new RiskRejectedException(
                    ("Продажа вышла бы за пределы позиции: в заявках уже %s, новая на %s, "
                            + "а куплено всего %s. Шорт не предусмотрен.")
                            .formatted(plain(alreadyOffered), plain(intent.quantity()), plain(position)));
        }
    }

    /** Сколько ещё не исполнено в открытых заявках указанной стороны. */
    private BigDecimal openQuantity(BotExecutionContext ctx, OrderSide side) {
        BigDecimal quantity = BigDecimal.ZERO;
        for (BotOrderEntity o : orderRepo.findAllByBotIdAndStatusIn(ctx.botId(), OPEN_STATUSES)) {
            if (o.isDryRun() != ctx.dryRun() || o.getSide() != side) {
                continue;
            }
            quantity = quantity.add(o.remainingQuantity());
        }
        return quantity;
    }

    /** Вызывается ПОСЛЕ успешной постановки: считаем только состоявшиеся заявки. */
    public void recordPlacement(UUID botId) {
        recentPlacements
                .computeIfAbsent(botId, __ -> new ConcurrentLinkedDeque<>())
                .addLast(Instant.now());
    }

    public void forget(UUID botId) {
        recentPlacements.remove(botId);
    }

    private void checkOrdersPerMinute(BotExecutionContext ctx) {
        Integer limit = ctx.maxOrdersPerMinute();
        if (limit == null || limit <= 0) {
            return;
        }

        ConcurrentLinkedDeque<Instant> marks = recentPlacements.computeIfAbsent(
                ctx.botId(), __ -> new ConcurrentLinkedDeque<>());

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(1));
        while (!marks.isEmpty() && marks.peekFirst().isBefore(cutoff)) {
            marks.pollFirst();
        }

        if (marks.size() >= limit) {
            throw new RiskRejectedException(
                    "Превышен лимит ордеров в минуту (" + limit + "). Похоже на разгон циклом.");
        }
    }

    private void checkOrdersPerDay(BotExecutionContext ctx) {
        Integer limit = ctx.maxOrdersPerDay();
        if (limit == null || limit <= 0) {
            return;
        }
        long today = orderRepo.countByBotIdAndCreatedAtAfter(ctx.botId(), Instant.now().minus(Duration.ofDays(1)));
        if (today >= limit) {
            throw new RiskRejectedException(
                    "Превышен суточный лимит ордеров (" + limit + "), выставлено " + today + ".");
        }
    }

    private void checkPosition(BotExecutionContext ctx, PlaceIntent intent) {
        BigDecimal limit = ctx.maxPositionQuantity();
        if (limit == null || limit.signum() <= 0) {
            return;
        }

        BigDecimal current = nvl(orderRepo.sumPositionQuantity(ctx.botId(), ctx.dryRun()));
        // Проверяем худший случай: как если бы заявка исполнилась целиком.
        BigDecimal projected = intent.side() == OrderSide.BUY
                ? current.add(intent.quantity())
                : current.subtract(intent.quantity());

        if (projected.abs().compareTo(limit) > 0) {
            throw new RiskRejectedException(
                    "Позиция вышла бы за лимит: " + plain(projected) + " при потолке " + plain(limit) + ".");
        }
    }

    private void checkCapital(BotExecutionContext ctx, PlaceIntent intent) {
        BigDecimal limit = ctx.maxCapital();
        if (limit == null || limit.signum() <= 0) {
            return;
        }
        // Продажа капитал не занимает — она его высвобождает.
        if (intent.side() != OrderSide.BUY) {
            return;
        }

        CapitalUsage usage = capitalUsage(ctx.botId(), ctx.dryRun());
        // compareTo, а не !=: журнал и книга приходят с разной шкалой BigDecimal,
        // и сравнение через equals объявляло бы расхождением 1 против 1.00.
        if (usage.journalPosition().compareTo(nvl(usage.inventory().openQuantity())) != 0) {
            throw new RiskRejectedException(
                    ("Новая покупка запрещена: позиция в журнале ордеров (%s) "
                            + "не совпадает с денежной книгой (%s).")
                            .formatted(plain(usage.journalPosition()),
                                    plain(usage.inventory().openQuantity())));
        }
        BigDecimal used = usage.amount();
        BigDecimal projected = used.add(intent.notional());

        if (projected.compareTo(limit) > 0) {
            throw new RiskRejectedException(
                    "Задействованный капитал вышел бы за лимит: " + projected.toPlainString()
                            + " при потолке " + limit.toPlainString() + ".");
        }
    }

    /** Деньги, занятые открытыми заявками на покупку и себестоимостью позиции из книги. */
    public BigDecimal usedCapital(BotExecutionContext ctx) {
        return capitalUsage(ctx.botId(), ctx.dryRun()).amount();
    }

    /**
     * То же самое, но для тех, у кого контекста исполнения нет.
     *
     * Нужно снаружи торгового цикла — например, чтобы ответить оператору, до какой
     * суммы бюджет вообще можно опустить. Считать это вторым способом было бы прямым
     * путём к двум разным ответам на один вопрос.
     */
    public CapitalUsage capitalUsage(UUID botId, boolean dryRun) {
        List<BotOrderEntity> open = orderRepo.findAllByBotIdAndStatusIn(botId, OPEN_STATUSES);

        BigDecimal reserved = BigDecimal.ZERO;
        for (BotOrderEntity o : open) {
            if (o.isDryRun() != dryRun || o.getSide() != OrderSide.BUY || o.getLimitPrice() == null) {
                continue;
            }
            reserved = reserved.add(o.getLimitPrice().multiply(o.remainingQuantity()));
        }

        BigDecimal journalPosition = nvl(orderRepo.sumPositionQuantity(botId, dryRun));
        Inventory inventory = accounting.inventory(botId, dryRun);
        return new CapitalUsage(reserved.add(inventory.costBasisOpen()), journalPosition, inventory);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Количество для человека: 0.000001 не должно превратиться в 1E-6. */
    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    public record CapitalUsage(BigDecimal amount, BigDecimal journalPosition, Inventory inventory) {
    }
}
