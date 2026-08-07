package ru.larionov.backend.accounting;

import java.math.BigDecimal;

/**
 * Неизменяемый снимок открытой позиции, восстановленный из денежной книги.
 *
 * Единица одна — ЕДИНИЦЫ БАЗОВОГО АКТИВА (штуки бумаг, монеты). Раньше здесь жили
 * сразу и лоты, и штуки, потому что заявки считались лотами, а оценка — штуками.
 * После перехода на дробное количество это одно и то же число, и второе представление
 * было бы лишним поводом перепутать единицы. Лотность биржи стала деталью исполнения
 * и живёт в {@code BotExecutionContext.exchangeLotSize}.
 *
 * {@code averageEntryPrice} — за ЕДИНИЦУ, поэтому
 * {@code costBasisOpen = openQuantity × averageEntryPrice} сходится без множителей.
 */
public record Inventory(
        BigDecimal openQuantity,
        BigDecimal costBasisOpen,
        BigDecimal averageEntryPrice
) {

    public static Inventory empty() {
        return new Inventory(BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    /** Есть ли что продавать. Сравнение только через compareTo: 0 и 0.00 равны по смыслу. */
    public boolean isEmpty() {
        return openQuantity == null || openQuantity.signum() == 0;
    }
}
