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
        String currency,
        /** GRID или RECOVERY: диапазон сетки либо восстановительный эпизод. */
        String kind,
        /** LONG или SHORT. */
        String direction,
        /** Торговалось ли поколение с плечом. */
        boolean margin,
        /** Цена входа в плечо. Только у восстановительного эпизода. */
        BigDecimal entryPrice,
        /** Расчётная цена выхода в ноль. Только у восстановительного эпизода. */
        BigDecimal targetPrice,
        /** Множитель переворота. Только у восстановительного эпизода. */
        BigDecimal multiplier
) {

    /** Итог поколения без сведений о режиме: лонговая сетка без маржи, как было раньше. */
    public GridGenerationDto(long generation, BigDecimal lowerPrice, BigDecimal upperPrice,
                             Integer levels, String origin, Instant startedAt, Instant endedAt,
                             boolean active, BigDecimal transitionCost, int cycles,
                             BigDecimal cyclesPnl, BigDecimal totalPnl, String currency) {
        this(generation, lowerPrice, upperPrice, levels, origin, startedAt, endedAt, active,
                transitionCost, cycles, cyclesPnl, totalPnl, currency, "GRID", "LONG", false,
                null, null, null);
    }

    /** Итог поколения без цен эпизода: обычная строка сетки. */
    public GridGenerationDto(long generation, BigDecimal lowerPrice, BigDecimal upperPrice,
                             Integer levels, String origin, Instant startedAt, Instant endedAt,
                             boolean active, BigDecimal transitionCost, int cycles,
                             BigDecimal cyclesPnl, BigDecimal totalPnl, String currency,
                             String kind, String direction, boolean margin) {
        this(generation, lowerPrice, upperPrice, levels, origin, startedAt, endedAt, active,
                transitionCost, cycles, cyclesPnl, totalPnl, currency, kind, direction, margin,
                null, null, null);
    }

    /** Строка для уведомления в Telegram и отметки в книге операций. */
    public String humanSummary() {
        return ("Итог поколения %d (%s..%s)%s: циклов %d, P/L циклов %s, "
                + "стоимость перехода %s, итого %s")
                .formatted(generation,
                        lowerPrice == null ? "—" : lowerPrice.toPlainString(),
                        upperPrice == null ? "—" : upperPrice.toPlainString(),
                        margin ? " %s, плечо".formatted(directionLabel()) : "",
                        cycles,
                        MoneyFormat.signed(cyclesPnl, currency),
                        MoneyFormat.signed(transitionCost, currency),
                        MoneyFormat.signed(totalPnl, currency));
    }

    private String directionLabel() {
        return "SHORT".equals(direction) ? "шорт" : "лонг";
    }
}
