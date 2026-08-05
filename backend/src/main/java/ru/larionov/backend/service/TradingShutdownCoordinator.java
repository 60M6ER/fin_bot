package ru.larionov.backend.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.runtime.TradingScheduler;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Гасит торговлю в правильном порядке — и при обычной остановке, и при перезапуске.
 *
 * <h3>Зачем это отдельный бин</h3>
 * Порядок разрушения контекста здесь работал против нас. {@link ExchangeRuntimeService}
 * зависит от {@link BotRuntimeService}, поэтому Spring уничтожает его ПЕРВЫМ — то есть
 * закрывает gRPC-канал раньше, чем боты успевают снять свои заявки, а снимают они их
 * именно через этот канал ({@code StrategyBotHandler.stop()}). Заявки при этом остаются
 * жить на бирже до конца сессии и могут исполниться без встречных продаж и без лимитов.
 *
 * Починить это через {@code @DependsOn} на BotRuntimeService нельзя: обратное ребро
 * уже существует, и получился бы настоящий цикл зависимостей.
 *
 * Зато этот бин зависит от ВСЕХ четырёх участников, а значит уничтожается раньше любого
 * из них — и успевает провести остановку в осмысленном порядке. Все вызываемые
 * {@code shutdown()} идемпотентны, поэтому их повторный вызов из собственных
 * {@code @PreDestroy} безвреден.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingShutdownCoordinator {

    private final RuntimeSupervisor supervisor;
    private final BotRuntimeService botRuntimeService;
    private final ExchangeRuntimeService exchangeRuntimeService;
    private final TradingScheduler scheduler;

    private final AtomicBoolean done = new AtomicBoolean(false);

    @PreDestroy
    public void onContextShutdown() {
        shutdownInOrder();
    }

    /**
     * Идемпотентна: вызывается и кнопкой перезапуска, и хуком контекста.
     * Порядок фиксированный и обоснован тем, кто чем владеет.
     */
    public void shutdownInOrder() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        log.info("Останавливаю торговлю в управляемом порядке");

        // 1. Супервизор — первым: иначе он поднимет обратно то, что мы гасим.
        step("супервизор", supervisor::shutdown);
        // 2. Боты — пока канал биржи ещё жив: им нужно снять заявки.
        step("боты", botRuntimeService::shutdown);
        // 3. Подключения и стримы — после того, как боты отработали.
        step("подключения", exchangeRuntimeService::shutdown);
        // 4. Планировщик тиков — последним: его задачи уже никому не нужны.
        step("планировщик", scheduler::shutdown);
    }

    private void step(String what, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // Сбой одного шага не должен мешать остальным: незакрытые заявки хуже
            // любой ошибки в логе.
            log.error("Остановка ({}) завершилась ошибкой", what, e);
        }
    }
}
