package ru.larionov.backend.dto;

import ru.larionov.backend.execution.BotOrderView;

import java.math.BigDecimal;
import java.util.List;

/**
 * Что бот делает прямо сейчас — минимум для контроля из UI.
 *
 * @param positionLots позиция по журналу: куплено минус продано, в лотах
 * @param queueSize    длина очереди событий; растущая очередь означает, что бот не успевает
 */
public record BotTradingStateDto(
        boolean running,
        boolean dryRun,
        long positionLots,
        BigDecimal reservedByBuyOrders,
        int openOrdersCount,
        int queueSize,
        List<BotOrderView> orders
) {}
