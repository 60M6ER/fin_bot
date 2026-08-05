package ru.larionov.backend.runtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.accounting.AccountingService;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.execution.*;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.service.BotEventService;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeRuntimeService;
import ru.larionov.backend.service.StrategyStateService;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.StrategyFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

/**
 * Собирает работающего бота из конфигурации: стратегия, гейтвей, лимиты, подписки.
 *
 * Все отказы здесь — осознанные и с внятным текстом: лучше не запуститься с объяснением,
 * чем запуститься наполовину и торговать вслепую.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategyBotHandlerFactory {

    private final ExchangeRuntimeService exchangeRuntimeService;
    private final StrategyFactory strategyFactory;
    private final BotOrderRepository orderRepo;
    private final RiskGuard riskGuard;
    private final BotEventService events;
    private final AccountingService accounting;
    private final TradingScheduler scheduler;
    private final StrategyStateService strategyStateService;
    private final LastPriceCache lastPriceCache;
    private final ObjectMapper objectMapper;

    public StrategyBotHandler create(BotEntity bot, Consumer<String> onStopRequested, Runnable onFatal) {
        BotRuntimeConfig config = parseConfig(bot);

        if (!config.hasInstrument()) {
            throw new IllegalStateException(
                    "В конфигурации бота не задан instrumentUid — торговать нечем.");
        }

        // Подключение обязано быть поднято: клиент и стримы живут в нём.
        ExchangeHandler exchangeHandler = exchangeRuntimeService.get(bot.getExchangeConnectionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Подключение бота не активно. Запустите его перед запуском бота."));

        AccountId accountId = exchangeHandler.tradingAccountId();
        InstrumentId instrumentId = new InstrumentId(config.instrumentUid(), null);

        // Лотность и шаг цены нужны стратегии для округления — тянем один раз при старте.
        TradingConstraints constraints;
        try {
            constraints = exchangeHandler.client().instruments().getConstraints(instrumentId);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось получить параметры инструмента " + config.instrumentUid()
                            + ": " + e.getMessage(), e);
        }

        BotExecutionContext execContext = new BotExecutionContext(
                bot.getId(),
                bot.getExchangeConnectionId(),
                accountId,
                instrumentId,
                config.dryRun(),
                constraints.lot(),
                config.maxCapital(),
                config.maxPositionLots(),
                config.maxOrdersPerDay(),
                config.maxOrdersPerMinute()
        );

        ExecutionGateway gateway = config.dryRun()
                ? new PaperExecutionGateway(orderRepo, riskGuard, events, accounting)
                : new LiveExecutionGateway(orderRepo, riskGuard, events, accounting, exchangeHandler::client);

        Strategy strategy = strategyFactory.create(bot);

        log.info("Bot {} собран: strategy={}, instrument={}, account={}, dryRun={}",
                bot.getId(), bot.getStrategyType(), config.instrumentUid(), accountId.value(), config.dryRun());

        return new StrategyBotHandler(
                bot, config, strategy, exchangeHandler, gateway, execContext,
                constraints, events, scheduler, strategyStateService, accounting,
                lastPriceCache, onStopRequested, onFatal);
    }

    private BotRuntimeConfig parseConfig(BotEntity bot) {
        try {
            String json = bot.getStrategyConfig() == null || bot.getStrategyConfig().isBlank()
                    ? "{}" : bot.getStrategyConfig();
            return objectMapper.readValue(json, BotRuntimeConfig.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось разобрать конфигурацию бота: " + e.getMessage(), e);
        }
    }

}
