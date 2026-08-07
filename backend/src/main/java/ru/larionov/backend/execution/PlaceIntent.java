package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;

/**
 * Намерение выставить ордер. Стратегия описывает, чего хочет, а решение —
 * можно ли и как именно — принимает гейтвей: он проверяет лимиты, генерирует
 * clientOrderId и ведёт журнал.
 *
 * @param quantity  количество в ЕДИНИЦАХ БАЗОВОГО АКТИВА (штуки, монеты), дробное.
 *                  Приведение к заявочным единицам биржи и к её шагу количества —
 *                  дело гейтвея, стратегия про лотность не знает
 * @param gridLevel уровень сетки, к которому относится ордер (для не-сеточных стратегий null)
 */
public record PlaceIntent(
        OrderSide side,
        BigDecimal quantity,
        BigDecimal limitPrice,
        Integer gridLevel
) {
    public PlaceIntent {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be > 0");
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("limitPrice must be > 0");
        }
    }

    /** Сумма заявки: с ней сравниваются минимальная сумма биржи и лимит капитала. */
    public BigDecimal notional() {
        return limitPrice.multiply(quantity);
    }
}
