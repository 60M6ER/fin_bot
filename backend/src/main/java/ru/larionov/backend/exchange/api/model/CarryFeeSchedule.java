package ru.larionov.backend.exchange.api.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Плата за перенос непокрытой позиции через ночь.
 *
 * Это НЕ комиссия, и в {@link FeeInfo} ей не место ни по смыслу, ни по размерности.
 * Комиссия берётся с оборота в момент сделки; перенос — с УДЕРЖАНИЯ, посуточно, и
 * зависит от размера позиции ступенчато, а не пропорционально. Для шорта, живущего
 * дольше дня, это основная статья издержек — и именно она превращает «безубыточный
 * откуп» в убыточный, если её не считать.
 *
 * <h3>Почему ставка задаётся человеком</h3>
 * Тарифного API у брокера нет — по той же причине, по которой руками задаётся
 * {@code commissionRate}. Умолчание намеренно консервативное: забытая настройка
 * обязана делать бота осторожнее, а не смелее. Заниженный перенос молча пропустит
 * в торговлю сетку, которая не окупает удержание.
 *
 * @param tiers            ступени по размеру позиции, от меньшей к большей
 * @param defaultDailyRate суточная ставка, если позиция не попала ни в одну ступень
 */
public record CarryFeeSchedule(
        List<Tier> tiers,
        BigDecimal defaultDailyRate
) {

    /**
     * Ставка на сутки, применяемая по умолчанию.
     *
     * Порядок величины взят с массовых тарифов брокера и заведомо не занижен:
     * ошибиться здесь в большую сторону значит отказаться от тесной сетки,
     * в меньшую — торговать в минус, не заметив этого.
     */
    public static final BigDecimal DEFAULT_DAILY_RATE = new BigDecimal("0.0007");

    /**
     * Ступень тарифа.
     *
     * @param uptoNotional верхняя граница позиции, до которой действует ступень
     *                     (включительно); null — «и всё, что выше»
     * @param dailyRate    суточная ставка от стоимости позиции
     */
    public record Tier(BigDecimal uptoNotional, BigDecimal dailyRate) {

        public Tier {
            if (dailyRate == null || dailyRate.signum() < 0) {
                throw new IllegalArgumentException("Ставка переноса не может быть отрицательной");
            }
        }
    }

    public static CarryFeeSchedule defaults() {
        return new CarryFeeSchedule(List.of(), DEFAULT_DAILY_RATE);
    }

    public CarryFeeSchedule {
        if (defaultDailyRate == null || defaultDailyRate.signum() < 0) {
            defaultDailyRate = DEFAULT_DAILY_RATE;
        }
        if (tiers == null) {
            tiers = List.of();
        } else {
            // Порядок задаём сами, а не полагаемся на того, кто заполнял настройки:
            // перепутанные местами ступени дали бы тариф, зависящий от порядка строк.
            List<Tier> sorted = new ArrayList<>(tiers);
            sorted.sort(Comparator.comparing(
                    t -> t.uptoNotional() == null ? null : t.uptoNotional(),
                    Comparator.nullsLast(Comparator.naturalOrder())));
            tiers = List.copyOf(sorted);
        }
    }

    /**
     * Суточная ставка для позиции указанного размера.
     *
     * Размер берётся ПО МОДУЛЮ: перенос платится за факт непокрытой позиции,
     * а её знак на цену удержания не влияет.
     */
    public BigDecimal dailyRate(BigDecimal notional) {
        if (notional == null) {
            return defaultDailyRate;
        }
        BigDecimal size = notional.abs();
        for (Tier tier : tiers) {
            if (tier.uptoNotional() == null || size.compareTo(tier.uptoNotional()) <= 0) {
                return tier.dailyRate();
            }
        }
        return defaultDailyRate;
    }

    /** Во сколько обойдётся сутки удержания позиции такого размера. */
    public BigDecimal dailyCost(BigDecimal notional) {
        if (notional == null || notional.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return notional.abs().multiply(dailyRate(notional)).setScale(9, RoundingMode.HALF_UP);
    }

    /** Во сколько обойдётся удержание в течение указанного числа суток. */
    public BigDecimal costFor(BigDecimal notional, int days) {
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        return dailyCost(notional).multiply(BigDecimal.valueOf(days));
    }
}
