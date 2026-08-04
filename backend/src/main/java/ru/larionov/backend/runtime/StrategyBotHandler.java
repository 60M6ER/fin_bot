package ru.larionov.backend.runtime;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.MarketDataStreamService;
import ru.larionov.backend.exchange.api.OrdersStreamService;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.exchange.api.model.market.TradingStatusEvent;
import ru.larionov.backend.exchange.api.model.order.OrderState;
import ru.larionov.backend.execution.*;
import ru.larionov.backend.service.BotEventService;
import ru.larionov.backend.service.BotRuntimeService;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.strategy.*;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Реальный хендлер бота: собирает движок и держит его жизненный цикл.
 *
 * Заменяет NoopBotHandler — то самое последнее оборванное звено, из-за которого
 * активация бота раньше сводилась к записи флага в БД.
 */
@Slf4j
public final class StrategyBotHandler implements BotRuntimeService.BotHandler, BotEventListener {

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final UUID botId;
    private final Strategy strategy;
    private final StrategyContext context;
    private final ExecutionGateway gateway;
    private final BotExecutionContext execContext;
    private final ExchangeHandler exchangeHandler;
    private final BotEventService events;
    private final TradingScheduler scheduler;
    private final long tickIntervalSeconds;
    private final BotRuntimeConfig.PriceSource priceSource;
    private final Runnable onFatal;

    private final BotEventLoop loop;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> tickTask;

    StrategyBotHandler(BotEntity bot,
                       BotRuntimeConfig runtimeConfig,
                       Strategy strategy,
                       ExchangeHandler exchangeHandler,
                       ExecutionGateway gateway,
                       BotExecutionContext execContext,
                       TradingConstraints constraints,
                       BotEventService events,
                       TradingScheduler scheduler,
                       Runnable onFatal) {
        this.botId = bot.getId();
        this.strategy = strategy;
        this.exchangeHandler = exchangeHandler;
        this.gateway = gateway;
        this.execContext = execContext;
        this.events = events;
        this.scheduler = scheduler;
        this.tickIntervalSeconds = runtimeConfig.tickIntervalSeconds();
        this.priceSource = runtimeConfig.priceSource();
        this.onFatal = onFatal;

        this.context = new DefaultStrategyContext(
                botId, execContext, gateway, constraints,
                exchangeHandler::client, events, Clock.systemUTC());

        this.loop = new BotEventLoop(botId, this, QUEUE_CAPACITY, MAX_CONSECUTIVE_FAILURES, this::fatal);
    }

    // ==============================
    // LIFECYCLE
    // ==============================

    @Override
    public void start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }

        loop.start();
        subscribeStreams();

        // Сверка ДО первого решения стратегии: после рестарта журнал может расходиться
        // с биржей, и действовать по несверенному состоянию — прямой путь к дублям.
        ReconcileResult reconciled = gateway.reconcile(execContext);
        log.info("Bot {} reconciled on start: openOrders={}, position={}",
                botId, reconciled.openOrders().size(), reconciled.positionLots());

        strategy.onStart(context);

        // Результат стартовой сверки обязан дойти до стратегии ДО первого её решения.
        // Раньше он оставался только в логе: стратегия узнавала о расхождении позиции
        // лишь на первом сторожевом тике, то есть спустя целый интервал, и всё это время
        // торговала по журналу, который сверка уже признала неверным.
        strategy.onReconcile(reconciled);

        tickTask = scheduler.scheduleTick(loop::submitTick, tickIntervalSeconds);

        events.emit(botId, BotEventLevel.INFO, BotEventType.BOT_STARTED,
                "Бот запущен%s. Активных ордеров: %d, позиция: %s".formatted(
                        gateway.isDryRun() ? " в бумажном режиме" : "",
                        reconciled.openOrders().size(),
                        reconciled.positionLots().toPlainString()),
                Map.of());
    }

    @Override
    public void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        ScheduledFuture<?> task = tickTask;
        tickTask = null;
        if (task != null) {
            task.cancel(false);
        }

        try {
            strategy.onStop();
        } catch (Exception e) {
            log.warn("Bot {} strategy.onStop failed: {}", botId, e.getMessage(), e);
        }

        // Очередь дорабатывается: не бросаем бота на полушаге.
        loop.close();

        String cancelReport = cancelOutstandingOrders();

        events.emit(botId, BotEventLevel.INFO, BotEventType.BOT_STOPPED,
                "Бот остановлен. " + cancelReport, Map.of());
    }

    /**
     * Снимает заявки бота при остановке.
     *
     * Это не удобство, а требование безопасности: остановленный бот не управляет
     * своими заявками, но на бирже они остаются живыми и могут исполниться в любой
     * момент до конца сессии — уже без встречных продаж и без учёта лимитов.
     * Пользователь, нажавший «Остановить», вправе считать, что торговля прекратилась.
     */
    private String cancelOutstandingOrders() {
        try {
            int open = gateway.openOrders(botId).size();
            if (open == 0) {
                return "Активных заявок не было.";
            }

            int cancelled = gateway.cancelAll(execContext);
            int left = gateway.openOrders(botId).size();

            if (left > 0) {
                // Честно сообщаем, а не рапортуем об успехе: незакрытая заявка
                // на бирже — это деньги, которыми никто не управляет.
                return "Снято заявок: %d, НЕ удалось снять: %d — проверьте их в приложении брокера."
                        .formatted(cancelled, left);
            }
            return "Снято заявок: %d.".formatted(cancelled);

        } catch (Exception e) {
            log.error("Bot {}: не удалось снять заявки при остановке: {}", botId, e.getMessage(), e);
            return "ВНИМАНИЕ: снять заявки не удалось (%s). Проверьте их в приложении брокера."
                    .formatted(e.getMessage());
        }
    }

    /**
     * Подписки живут на уровне подключения и переиспользуются несколькими ботами,
     * поэтому при остановке бота мы их НЕ снимаем — иначе оборвали бы соседей.
     * Слушатели после остановки становятся безвредными: BotEventLoop игнорирует
     * всё, что приходит после close().
     */
    private void subscribeStreams() {
        ExchangeClient client = exchangeHandler.client();
        InstrumentId instrument = execContext.instrumentId();
        Set<InstrumentId> instruments = Set.of(instrument);

        client.marketDataStream().ifPresent(md -> {
            if (priceSource == BotRuntimeConfig.PriceSource.ORDER_BOOK) {
                md.subscribeOrderBook(instruments, 1, book -> {
                    if (!isOurs(book.instrumentId())) return;
                    // Из стакана берём середину: для решений сетки этого достаточно.
                    if (book.bids().isEmpty() || book.asks().isEmpty()) return;
                    var mid = book.bids().get(0).price().value()
                            .add(book.asks().get(0).price().value())
                            .divide(java.math.BigDecimal.valueOf(2), 9, java.math.RoundingMode.HALF_UP);
                    loop.submitPrice(new LastPrice(book.instrumentId(),
                            new ru.larionov.backend.exchange.api.model.market.Price(mid, null), book.ts()));
                });
            } else {
                md.subscribeLastPrice(instruments, p -> {
                    if (isOurs(p.instrumentId())) loop.submitPrice(p);
                });
            }

            md.subscribeTradingStatus(instruments, s -> {
                if (isOurs(s.instrumentId())) loop.submitTradingStatus(s);
            });

            md.onReconnect(loop::submitReconnect);
        });

        client.ordersStream().ifPresent(os -> {
            os.subscribeOrderStates(execContext.accountId(), loop::submitOrderUpdate);
            os.onReconnect(loop::submitReconnect);
        });
    }

    /**
     * Стримы отдают события по всем подпискам подключения, поэтому каждый бот
     * фильтрует своё сам.
     */
    private boolean isOurs(InstrumentId id) {
        if (id == null) return false;
        String ours = execContext.instrumentId().primary();
        return ours != null && (ours.equals(id.uid()) || ours.equals(id.figi()));
    }

    private void fatal(String reason) {
        events.emit(botId, BotEventLevel.ERROR, BotEventType.ERROR,
                "Бот остановлен из-за сбоя: " + reason, Map.of());
        if (onFatal != null) {
            onFatal.run();
        }
    }

    // ==============================
    // BotEventListener — вызывается строго последовательно
    // ==============================

    @Override
    public void onPrice(LastPrice price) {
        // В бумажном режиме цена двигает симуляцию исполнения: сначала «исполняем»,
        // потом отдаём стратегии, чтобы она увидела уже актуальное состояние.
        for (BotOrderView filled : gateway.onPrice(execContext, price)) {
            strategy.onOrderUpdate(filled);
        }
        strategy.onPrice(price);
    }

    @Override
    public void onOrderUpdate(OrderState state) {
        // Сначала журнал, потом стратегия: она получает уже записанное состояние
        // вместе с уровнем сетки, которого в сыром событии биржи нет.
        gateway.applyOrderEvent(execContext, state).ifPresent(strategy::onOrderUpdate);
    }

    @Override
    public void onTradingStatus(TradingStatusEvent event) {
        strategy.onTradingStatus(event);
    }

    @Override
    public void onStreamReconnect() {
        events.emit(botId, BotEventLevel.WARN, BotEventType.STREAM_RECONNECTED,
                "Стрим переподключился — сверяюсь с биржей", Map.of());

        // Сверка ДО того, как стратегия что-то предпримет: за время разрыва
        // ордера могли исполниться, а событий об этом мы не получили.
        strategy.onReconcile(gateway.reconcile(execContext));
        strategy.onStreamReconnect();
    }

    @Override
    public void onTick() {
        strategy.onTick();
    }

    public UUID botId() {
        return botId;
    }

    /** Для UI: сколько событий ждёт обработки. Растущая очередь — признак беды. */
    public int queueSize() {
        return loop.queueSize();
    }
}
