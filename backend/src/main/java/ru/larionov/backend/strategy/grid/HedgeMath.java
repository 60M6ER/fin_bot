package ru.larionov.backend.strategy.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Арифметика восстановительного плеча.
 *
 * Замысел: вместо того чтобы закрыть убыточную позицию и зафиксировать убыток, бот
 * продаёт (или откупает) её с множителем. Часть сделки закрывает старую позицию, а
 * остаток открывает противоположную — и уже она отбивает убыток, если рынок продолжит
 * идти в ту же сторону. Плечо в {@code k} означает, что для возврата в ноль нужно
 * пройти лишь {@code 1/(k−1)} исходного убытка по цене.
 *
 * <h3>Почему это отдельный класс</h3>
 * Здесь считаются деньги, и считаются один раз — дальше по этой цене выставляется
 * заявка, от неё же отсчитывается стоп и срок. Формула вынесена чистой функцией
 * ровно затем, чтобы её можно было проверить числами, не поднимая ни стратегию,
 * ни биржу: ошибка тут не падает, а тихо превращается в убыток.
 *
 * <h3>Что входит в расчёт</h3>
 * Комиссия входит ДВАЖДЫ — на входе и на выходе, — и перенос непокрытой позиции за
 * всё время удержания. Наивная формула {@code P_s − |убыток|/((k−1)·N)} игнорирует
 * и то и другое, и потому систематически обещает безубыток раньше, чем он наступит.
 */
public final class HedgeMath {

    private HedgeMath() {
    }

    /**
     * Что получится, если перевернуть позицию с множителем.
     *
     * @param direction  направление ЗАКРЫВАЕМОЙ позиции: лонг переворачивается продажей,
     *                   шорт — покупкой
     * @param quantity   размер закрываемой позиции по модулю
     * @param costBasis  себестоимость этой позиции, знаковая (как в денежной книге)
     * @param entryPrice цена, по которой переворот исполняется
     * @param feeRate    ставка комиссии за одну сторону
     * @param multiplier множитель k: продаём (откупаем) в k раз больше, чем закрываем
     * @param carryDailyRate суточная ставка переноса непокрытой позиции
     * @param holdDays   на сколько суток закладываемся при удержании плеча
     */
    public static Plan plan(GridDirection direction,
                            BigDecimal quantity,
                            BigDecimal costBasis,
                            BigDecimal entryPrice,
                            BigDecimal feeRate,
                            BigDecimal multiplier,
                            BigDecimal carryDailyRate,
                            int holdDays) {

        if (quantity == null || quantity.signum() <= 0) {
            return Plan.refused("Переворачивать нечего: позиция пуста.");
        }
        if (entryPrice == null || entryPrice.signum() <= 0) {
            return Plan.refused("Нет цены, по которой переворот мог бы исполниться.");
        }
        if (multiplier == null || multiplier.compareTo(BigDecimal.ONE) <= 0) {
            return Plan.refused("Множитель плеча должен быть больше единицы: "
                    + "при k=1 сделка просто закрывает позицию и отбивать убыток нечем.");
        }

        BigDecimal fee = feeRate == null ? BigDecimal.ZERO : feeRate;
        BigDecimal carry = carryDailyRate == null ? BigDecimal.ZERO : carryDailyRate;
        int days = Math.max(0, holdDays);

        // Размер противоположной ноги: k·N закрывает N и открывает (k−1)·N.
        BigDecimal hedgeQuantity = quantity.multiply(multiplier.subtract(BigDecimal.ONE));
        BigDecimal totalQuantity = quantity.multiply(multiplier);

        // Деньги, которые принесёт закрытие старой позиции, и убыток по ней.
        // Для лонга закрытие — продажа: получаем цену за вычетом комиссии.
        // Для шорта закрытие — покупка: платим цену плюс комиссию.
        BigDecimal closingCash = direction == GridDirection.LONG
                ? quantity.multiply(entryPrice).multiply(BigDecimal.ONE.subtract(fee))
                : quantity.multiply(entryPrice).multiply(BigDecimal.ONE.add(fee)).negate();
        BigDecimal realizedOnClose = closingCash.subtract(nvl(costBasis));

        // Деньги открытия плеча и плата за его удержание.
        BigDecimal hedgeNotional = hedgeQuantity.multiply(entryPrice);
        BigDecimal hedgeOpenCash = direction == GridDirection.LONG
                ? hedgeNotional.multiply(BigDecimal.ONE.subtract(fee))
                : hedgeNotional.multiply(BigDecimal.ONE.add(fee)).negate();
        BigDecimal carryCost = hedgeNotional.multiply(carry).multiply(BigDecimal.valueOf(days));

        /*
         * Цена, при которой эпизод выходит в ноль.
         *
         * Итог = убыток закрытия + деньги открытия плеча − деньги его закрытия − перенос.
         * Приравниваем к нулю и выражаем цену закрытия плеча.
         */
        BigDecimal denominator = direction == GridDirection.LONG
                ? hedgeQuantity.multiply(BigDecimal.ONE.add(fee))
                : hedgeQuantity.multiply(BigDecimal.ONE.subtract(fee));
        if (denominator.signum() == 0) {
            return Plan.refused("Размер плеча оказался нулевым.");
        }

        BigDecimal numerator = realizedOnClose.add(hedgeOpenCash).subtract(carryCost);
        BigDecimal targetPrice = direction == GridDirection.LONG
                ? numerator.divide(denominator, 9, RoundingMode.HALF_UP)
                : numerator.negate().divide(denominator, 9, RoundingMode.HALF_UP);

        if (targetPrice.signum() <= 0) {
            return Plan.refused(("Цена безубытка получилась неположительной (%s): плечо ×%s "
                    + "не способно отбить этот убыток. Нужна либо ликвидация, либо больший множитель.")
                    .formatted(targetPrice.toPlainString(), multiplier.toPlainString()));
        }

        // Цель обязана лежать по ту сторону входа, в которую мы и рассчитываем на движение:
        // лонг переворачивается в шорт и ждёт падения, шорт — роста. Цель по неверную
        // сторону означает, что арифметика где-то соврала, и торговать по ней нельзя.
        boolean sane = direction == GridDirection.LONG
                ? targetPrice.compareTo(entryPrice) < 0
                : targetPrice.compareTo(entryPrice) > 0;
        if (!sane) {
            return Plan.refused(("Цена безубытка %s оказалась по ту же сторону от входа %s. "
                    + "Так не бывает при настоящем убытке — расчёт не сходится.")
                    .formatted(targetPrice.toPlainString(), entryPrice.toPlainString()));
        }

        return new Plan(true, null, totalQuantity, hedgeQuantity, entryPrice, targetPrice,
                realizedOnClose, carryCost, hedgeNotional);
    }

    /**
     * @param accepted     можно ли переворачивать; false — работать по этому плану нельзя
     * @param refusal      человекочитаемая причина отказа
     * @param totalQuantity сколько всего продать (откупить) одной заявкой
     * @param hedgeQuantity какая часть из них останется противоположной позицией
     * @param entryPrice   цена входа
     * @param targetPrice  цена, при которой эпизод выходит в ноль
     * @param realizedOnClose результат закрытия старой позиции, обычно отрицательный
     * @param carryCost    во что обойдётся удержание плеча за заложенный срок
     * @param hedgeNotional номинал плеча — им меряются потолки и обеспечение
     */
    public record Plan(
            boolean accepted,
            String refusal,
            BigDecimal totalQuantity,
            BigDecimal hedgeQuantity,
            BigDecimal entryPrice,
            BigDecimal targetPrice,
            BigDecimal realizedOnClose,
            BigDecimal carryCost,
            BigDecimal hedgeNotional
    ) {

        static Plan refused(String reason) {
            return new Plan(false, reason, null, null, null, null, null, null, null);
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
