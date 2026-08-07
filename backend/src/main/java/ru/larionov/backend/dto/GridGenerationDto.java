package ru.larionov.backend.dto;

import ru.larionov.backend.service.MoneyFormat;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Итог одного поколения сетки: во сколько обошёлся вход в него и что оно принесло.
 *
 * Три числа отвечают на вопрос «стоила ли эта перестановка того»:
 * <ul>
 *   <li>{@code transitionCost} — убыток, зафиксированный принудительным закрытием
 *       позиции ПЕРЕД этим диапазоном. Со знаком: обычно отрицателен, у поколения,
 *       в которое вошли без ликвидации (старт бота, перестановка вверх), — ноль;</li>
 *   <li>{@code cyclesPnl} — сколько заработано закрытыми циклами внутри диапазона;</li>
 *   <li>{@code totalPnl} — их сумма, то есть чистый результат поколения.</li>
 * </ul>
 *
 * Открытая позиция в {@code cyclesPnl} не входит: это реализованный результат,
 * а незакрытые циклы действующего поколения ещё могут закончиться как угодно.
 */
public record GridGenerationDto(
        long generation,
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        Integer levels,
        String origin,
        Instant startedAt,
        Instant endedAt,
        boolean active,
        BigDecimal transitionCost,
        int cycles,
        BigDecimal cyclesPnl,
        BigDecimal totalPnl,
        String currency
) {

    /** Строка для уведомления в Telegram и отметки в книге операций. */
    public String humanSummary() {
        return ("Итог поколения %d (%s..%s): циклов %d, P/L циклов %s, "
                + "стоимость перехода %s, итого %s")
                .formatted(generation,
                        lowerPrice.toPlainString(), upperPrice.toPlainString(),
                        cycles,
                        MoneyFormat.signed(cyclesPnl, currency),
                        MoneyFormat.signed(transitionCost, currency),
                        MoneyFormat.signed(totalPnl, currency));
    }
}
