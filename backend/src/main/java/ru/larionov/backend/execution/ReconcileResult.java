package ru.larionov.backend.execution;

import java.math.BigDecimal;
import java.util.List;

/**
 * Итог сверки с биржей.
 *
 * @param openOrders       ордера, живые на бирже после сверки
 * @param position         фактическая позиция в единицах базового актива: истина
 *                         в последней инстанции — биржа, а не наш журнал
 * @param usedCapital      деньги, занятые активными заявками и позицией
 * @param resolvedPending  сколько «повисших» записей удалось дорешить
 * @param adoptedOrphans   сколько ордеров нашлось на бирже, но отсутствовало в журнале
 * @param positionMismatch расхождение позиции журнала с биржей — признак того,
 *                         что мы что-то пропустили; повод для внимания, а не для торговли
 */
public record ReconcileResult(
        List<BotOrderView> openOrders,
        BigDecimal position,
        BigDecimal usedCapital,
        int resolvedPending,
        int adoptedOrphans,
        BigDecimal positionMismatch
) {

    public boolean hasFindings() {
        return resolvedPending > 0
                || adoptedOrphans > 0
                || (positionMismatch != null && positionMismatch.signum() != 0);
    }
}
