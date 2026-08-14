package ru.larionov.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Сколько у бота денег и сколько он заработал — с учётом открытых лотов по текущей цене.
 *
 * Надмножество {@link BotAccountingDto}: те же имена полей плюс рыночная оценка и
 * бюджет. Одна форма и для списков ботов, и для карточки бота — чтобы фронтенд
 * форматировал деньги одним хелпером, а не тремя расходящимися копиями.
 *
 * <h3>Как читать null</h3>
 * <ul>
 *   <li>нет цены в кэше стрима → {@code lastPrice}, {@code marketValue},
 *       {@code unrealizedPnl}, {@code totalPnl}, {@code equity} = null,
 *       но {@code realizedPnl} есть всегда;</li>
 *   <li>бюджет не задан (боты, созданные до появления бюджета) → {@code budget},
 *       {@code workingBudget}, {@code equity} = null.</li>
 * </ul>
 *
 * <h3>Формулы</h3>
 * <pre>
 * marketValue   = openQuantity × lastPrice
 * unrealizedPnl = marketValue − costBasisOpen
 * totalPnl      = realizedPnl + unrealizedPnl
 * equity        = workingBudget + unrealizedPnl
 * usedMargin    = shortQuantity × lastPrice × ставка риска брокера
 * </pre>
 *
 * <h3>Обеспечение в баланс НЕ входит</h3>
 * И не должно: заёмные деньги не принадлежат боту. Короткая продажа приносит выручку,
 * но её ровно гасит отрицательная рыночная стоимость позиции, поэтому {@code equity}
 * остаётся «свои деньги плюс результат» и от плеча не раздувается. {@code usedMargin}
 * отвечает на другой вопрос — сколько обеспечения бот занял у брокера, — и живёт
 * отдельной величиной именно поэтому.
 * При COMPOUND {@code workingBudget = budget + realizedPnl}, при WITHDRAW
 * {@code workingBudget = budget}, а прибыль показывается в {@code withdrawnProfit}.
 * Итоговое богатство {@code equity + withdrawnProfit} в обеих политиках одинаково —
 * они отличаются тем, сколько денег бот держит в обороте, а не результатом.
 */
public record BotValuationDto(
        boolean dryRun,
        BigDecimal cashFlow,
        BigDecimal costBasisOpen,
        BigDecimal realizedPnl,
        BigDecimal paidCommission,
        /** Открытая позиция в ЕДИНИЦАХ БАЗОВОГО АКТИВА (штуки, монеты). */
        BigDecimal openQuantity,
        /** Средняя цена входа за одну такую единицу. */
        BigDecimal averageEntryPrice,
        String currency,

        BigDecimal lastPrice,
        Instant lastPriceAt,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl,
        BigDecimal totalPnl,

        BigDecimal budget,
        BigDecimal workingBudget,
        BigDecimal withdrawnProfit,
        BigDecimal equity,
        String profitPolicy,
        String sizingMode,

        /** Короткая часть позиции по модулю. Ноль у обычной лонговой сетки. */
        BigDecimal shortQuantity,
        /**
         * Занятое у брокера обеспечение под короткую позицию.
         *
         * null означает «не знаем ставку риска» — брокер её не сообщил или бот с этой
         * бумагой ни разу не поднимался. Это не ноль: ноль здесь законный ответ для
         * бота без шорта, и путать его с незнанием нельзя.
         */
        BigDecimal usedMargin
) {

    /** Оценка без маржинальных величин: бот без единой короткой позиции. */
    public BotValuationDto(boolean dryRun, BigDecimal cashFlow, BigDecimal costBasisOpen,
                           BigDecimal realizedPnl, BigDecimal paidCommission,
                           BigDecimal openQuantity, BigDecimal averageEntryPrice, String currency,
                           BigDecimal lastPrice, Instant lastPriceAt, BigDecimal marketValue,
                           BigDecimal unrealizedPnl, BigDecimal totalPnl,
                           BigDecimal budget, BigDecimal workingBudget, BigDecimal withdrawnProfit,
                           BigDecimal equity, String profitPolicy, String sizingMode) {
        this(dryRun, cashFlow, costBasisOpen, realizedPnl, paidCommission, openQuantity,
                averageEntryPrice, currency, lastPrice, lastPriceAt, marketValue, unrealizedPnl,
                totalPnl, budget, workingBudget, withdrawnProfit, equity, profitPolicy, sizingMode,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Заглушка для бота, по которому ещё нечего показать. */
    public static BotValuationDto empty(boolean dryRun) {
        return new BotValuationDto(
                dryRun, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, null,
                null, null, null, null, null,
                null, null, BigDecimal.ZERO, null, null, null,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
