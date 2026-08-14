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
 * <h3>Знак</h3>
 * {@code openQuantity} и {@code costBasisOpen} знаковые: у шорта они отрицательны.
 * Тождество {@code costBasisOpen = openQuantity × averageEntryPrice} сходится без
 * множителей и для шорта тоже — оба множителя меняют знак согласованно, поэтому
 * средняя цена входа остаётся ПОЛОЖИТЕЛЬНОЙ ценой, какой ей и полагается быть.
 *
 * @param longQuantity  длинная часть по модулю
 * @param shortQuantity короткая часть по модулю
 */
public record Inventory(
        BigDecimal openQuantity,
        BigDecimal costBasisOpen,
        BigDecimal averageEntryPrice,
        BigDecimal longQuantity,
        BigDecimal shortQuantity
) {

    /** Снимок без разбивки по сторонам: всё длинное, как было до появления шортов. */
    public Inventory(BigDecimal openQuantity, BigDecimal costBasisOpen, BigDecimal averageEntryPrice) {
        this(openQuantity, costBasisOpen, averageEntryPrice,
                openQuantity == null || openQuantity.signum() < 0 ? BigDecimal.ZERO : openQuantity,
                BigDecimal.ZERO);
    }

    public static Inventory empty() {
        return new Inventory(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Есть ли открытая позиция. Сравнение только через compareTo: 0 и 0.00 равны по смыслу.
     *
     * ВНИМАНИЕ: пусто по нетто ещё не значит, что позиции нет. Бот с равными длинной
     * и короткой ногами нетто-плоский, но обе его ноги живые и обе стоят денег.
     * Тем, кого интересует «есть ли вообще что-то открытое», нужен {@link #grossExposure()}.
     */
    public boolean isEmpty() {
        return openQuantity == null || openQuantity.signum() == 0;
    }

    /**
     * Вся открытая экспозиция без взаимозачёта сторон.
     *
     * Именно ею измеряется «бот действительно ничего не держит»: нетто-ноль при
     * живых ногах — это не отсутствие позиции, а совпадение.
     */
    public BigDecimal grossExposure() {
        return nvl(longQuantity).add(nvl(shortQuantity));
    }

    /** Книга держит обе стороны сразу — усреднять их цену входа бессмысленно. */
    public boolean mixed() {
        return nvl(longQuantity).signum() > 0 && nvl(shortQuantity).signum() > 0;
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
