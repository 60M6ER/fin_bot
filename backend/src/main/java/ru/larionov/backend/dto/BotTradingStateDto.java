package ru.larionov.backend.dto;

import ru.larionov.backend.execution.BotOrderView;
import ru.larionov.backend.strategy.StrategySnapshot;

import java.math.BigDecimal;
import java.util.List;

/**
 * Что бот делает прямо сейчас — минимум для контроля из UI.
 *
 * @param position     позиция по журналу: куплено минус продано, в единицах базового актива
 * @param queueSize    длина очереди событий; растущая очередь означает, что бот не успевает
 */
public record BotTradingStateDto(
        boolean running,
        boolean dryRun,
        BigDecimal position,
        BigDecimal reservedByBuyOrders,
        int openOrdersCount,
        int queueSize,
        StrategySnapshot strategySnapshot,
        List<BotOrderView> orders
) {}
