package ru.larionov.backend.execution;

import ru.larionov.backend.entity.BotOrderEntity;
import ru.larionov.backend.enums.GridRole;
import ru.larionov.backend.enums.OrderPurpose;
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
 * @param purpose   зачем заявка выставлена. Ликвидация и продажа пыли живут по своим
 *                  правилам, и отличать их по «уровень не задан» больше нельзя:
 *                  таких заявок стало две
 * @param role      набирает заявка позицию или закрывает набранное. В лонге это
 *                  выводится из стороны, в шорте — ровно наоборот, поэтому решает
 *                  стратегия: только она знает направление своего поколения
 */
public record PlaceIntent(
        OrderSide side,
        BigDecimal quantity,
        BigDecimal limitPrice,
        Integer gridLevel,
        OrderPurpose purpose,
        GridRole role
) {
    /** Обычная заявка лонговой сетки. */
    public PlaceIntent(OrderSide side, BigDecimal quantity, BigDecimal limitPrice, Integer gridLevel) {
        this(side, quantity, limitPrice, gridLevel, OrderPurpose.GRID, null);
    }

    /** Заявка с назначением, но без явной роли: роль выводится по лонговому правилу. */
    public PlaceIntent(OrderSide side, BigDecimal quantity, BigDecimal limitPrice,
                       Integer gridLevel, OrderPurpose purpose) {
        this(side, quantity, limitPrice, gridLevel, purpose, null);
    }

    public PlaceIntent {
        if (purpose == null) {
            purpose = OrderPurpose.GRID;
        }
        // Умолчание лонговое и совпадает с тем, которым заполняются старые записи
        // журнала. Шортовая сетка роль передаёт явно: там продажа — это открытие,
        // и молча угаданная роль означала бы неверно перестроенные партии.
        if (role == null) {
            role = BotOrderEntity.roleFromSide(side);
        }
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
