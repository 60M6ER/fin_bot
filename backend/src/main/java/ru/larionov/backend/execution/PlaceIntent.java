package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;

/**
 * Намерение выставить ордер. Стратегия описывает, чего хочет, а решение —
 * можно ли и как именно — принимает гейтвей: он проверяет лимиты, генерирует
 * clientOrderId и ведёт журнал.
 *
 * @param lots       количество в ЛОТАХ: T-Invest принимает только целые лоты
 * @param gridLevel  уровень сетки, к которому относится ордер (для не-сеточных стратегий null)
 */
public record PlaceIntent(
        OrderSide side,
        long lots,
        BigDecimal limitPrice,
        Integer gridLevel
) {
    public PlaceIntent {
        if (lots <= 0) {
            throw new IllegalArgumentException("lots must be > 0");
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("limitPrice must be > 0");
        }
    }
}
