package ru.larionov.backend.strategy.grid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import ru.larionov.backend.exchange.api.enums.CandleInterval;

import java.math.BigDecimal;

/**
 * Параметры сетки.
 *
 * Поля движка (instrumentUid, лимиты, dryRun) живут в том же JSON и разбираются
 * отдельно через BotRuntimeConfig — здесь только то, что нужно самой стратегии.
 *
 * Подключение сюда НЕ входит: связь бота с подключением хранится колонкой
 * bot.exchange_connection_id, чтобы не было двух расходящихся источников правды.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GridConfig(
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        /*
         * Обёртки, а не примитивы: Jackson не умеет класть отсутствующее поле
         * в примитив и падает с ошибкой десериализации вместо понятного сообщения
         * о том, какого параметра не хватает.
         */
        Integer levels,
        Long lotsPerOrder,
        Integer maxActiveOrders,
        RangeExitAction onRangeExit,
        BigDecimal minStepToCommissionRatio,
        Integer feeRefreshSeconds,
        Boolean enabled,
        Boolean autoRange,
        CandleInterval atrInterval,
        Integer atrPeriods,
        BigDecimal atrMultiplier,
        BigDecimal minHalfWidthPct,
        BigDecimal maxHalfWidthPct,
        UpperBreakoutAction onUpperBreakout,
        Integer breakoutConfirmSeconds,
        BigDecimal breakoutMarginPct,
        Integer replaceCooldownSeconds,
        Integer maxDownwardReplacements,
        BigDecimal maxRealizedLoss,
        /*
         * Бюджет бота — ВСЕГДА конкретная сумма, а не доля портфеля. Процент, если
         * пользователь его вводит, превращается в число в момент сохранения в UI:
         * иначе изменившийся портфель молча передвинул бы бюджет бота при первой же
         * перестройке сетки, то есть бот забрал бы деньги без команды.
         */
        BigDecimal budget,
        SizingMode sizingMode,
        ProfitPolicy profitPolicy
) {

    public enum SizingMode {
        /** Прежнее поведение: фиксированное число лотов из lotsPerOrder, бюджет не участвует. */
        FIXED_LOTS,
        /** Один размер на все уровни: floor(бюджет / Σ цена_i × лотность). */
        UNIFORM,
        /** Поровну денег на уровень: floor((бюджет/N) / (цена_i × лотность)). */
        PER_LEVEL
    }

    public enum ProfitPolicy {
        /** Прибыль реинвестируется: рабочий бюджет = бюджет + реализованный P/L. */
        COMPOUND,
        /** Прибыль выводится: рабочий бюджет остаётся равен бюджету. */
        WITHDRAW
    }

    public enum RangeExitAction {
        /** Перестать покупать, уже купленное продолжать продавать. Позиция замирает. */
        STOP_BUYING,
        /** Снять все заявки и остановить бота. Жёстче, но предсказуемее. */
        CANCEL_AND_STOP,
        /** Переставить сетку ниже с ограниченным бюджетом убытка. */
        REPLACE_LOWER
    }

    public enum UpperBreakoutAction {
        NOTHING,
        REPLACE_UPPER
    }

    public GridConfig(BigDecimal lowerPrice,
                      BigDecimal upperPrice,
                      Integer levels,
                      Long lotsPerOrder,
                      Integer maxActiveOrders,
                      RangeExitAction onRangeExit,
                      BigDecimal minStepToCommissionRatio,
                      Boolean enabled) {
        this(lowerPrice, upperPrice, levels, lotsPerOrder, maxActiveOrders,
                onRangeExit, minStepToCommissionRatio, null, enabled,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    public GridConfig {
        if (autoRange == null) {
            autoRange = false;
        }
        if (!autoRange) {
            if (lowerPrice == null || upperPrice == null) {
                throw new IllegalArgumentException("lowerPrice и upperPrice обязательны в ручном режиме");
            }
            if (lowerPrice.signum() <= 0) {
                throw new IllegalArgumentException("lowerPrice должен быть больше нуля");
            }
            if (upperPrice.compareTo(lowerPrice) <= 0) {
                throw new IllegalArgumentException("upperPrice должен быть больше lowerPrice");
            }
        }
        if (levels == null || levels <= 0) {
            throw new IllegalArgumentException("levels обязателен и должен быть больше нуля");
        }
        // Режим по умолчанию выводим из наличия бюджета: у старых ботов в JSON есть
        // lotsPerOrder и нет budget — они обязаны сохранить прежнее поведение до байта.
        if (sizingMode == null) {
            sizingMode = (budget == null) ? SizingMode.FIXED_LOTS : SizingMode.UNIFORM;
        }
        if (sizingMode == SizingMode.FIXED_LOTS) {
            if (lotsPerOrder == null || lotsPerOrder <= 0) {
                throw new IllegalArgumentException("lotsPerOrder обязателен и должен быть больше нуля");
            }
        } else if (budget == null || budget.signum() <= 0) {
            throw new IllegalArgumentException(
                    "budget обязателен и должен быть больше нуля, когда размер заявки считается от бюджета");
        }
        // lotsPerOrder в бюджетных режимах намеренно НЕ подставляем: любое забытое
        // чтение cfg.lotsPerOrder() должно упасть на старте, до единой заявки.
        // Подстановка «1» означала бы тихую торговлю одним лотом на реальные деньги.
        if (profitPolicy == null) {
            profitPolicy = ProfitPolicy.WITHDRAW;
        }
        if (maxActiveOrders == null || maxActiveOrders <= 0) {
            maxActiveOrders = levels;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (onRangeExit == null) {
            onRangeExit = RangeExitAction.STOP_BUYING;
        }
        // Требуемый запас шага над комиссией за оборот. 1.5 = «шаг окупает комиссию
        // минимум в полтора раза». Меньше 1.0 — торговля в убыток по построению.
        if (minStepToCommissionRatio == null || minStepToCommissionRatio.signum() <= 0) {
            minStepToCommissionRatio = new BigDecimal("1.5");
        }
        if (feeRefreshSeconds == null || feeRefreshSeconds <= 0) {
            feeRefreshSeconds = 3600;
        }
        if (atrInterval == null) {
            atrInterval = CandleInterval.H1;
        }
        if (atrPeriods == null || atrPeriods <= 0) {
            atrPeriods = 24;
        }
        if (atrMultiplier == null || atrMultiplier.signum() <= 0) {
            atrMultiplier = new BigDecimal("2.0");
        }
        if (minHalfWidthPct == null || minHalfWidthPct.signum() <= 0) {
            minHalfWidthPct = new BigDecimal("0.010");
        }
        if (maxHalfWidthPct == null || maxHalfWidthPct.signum() <= 0) {
            maxHalfWidthPct = new BigDecimal("0.150");
        }
        if (maxHalfWidthPct.compareTo(minHalfWidthPct) < 0) {
            throw new IllegalArgumentException("maxHalfWidthPct должен быть не меньше minHalfWidthPct");
        }
        if (onUpperBreakout == null) {
            onUpperBreakout = UpperBreakoutAction.NOTHING;
        }
        if (onUpperBreakout == UpperBreakoutAction.REPLACE_UPPER && !autoRange) {
            throw new IllegalArgumentException(
                    "Перестановка вверх доступна только при автоматическом диапазоне");
        }
        if (breakoutConfirmSeconds == null || breakoutConfirmSeconds <= 0) {
            breakoutConfirmSeconds = 300;
        }
        if (breakoutMarginPct == null || breakoutMarginPct.signum() < 0) {
            breakoutMarginPct = new BigDecimal("0.002");
        }
        if (replaceCooldownSeconds == null || replaceCooldownSeconds <= 0) {
            replaceCooldownSeconds = 1200;
        }
        if (maxDownwardReplacements == null || maxDownwardReplacements < 0) {
            maxDownwardReplacements = 0;
        }
        if (maxDownwardReplacements > 0
                && (maxRealizedLoss == null || maxRealizedLoss.signum() <= 0)) {
            throw new IllegalArgumentException(
                    "maxRealizedLoss обязателен при разрешённых перестановках вниз");
        }
        if (onRangeExit == RangeExitAction.REPLACE_LOWER) {
            if (!autoRange) {
                throw new IllegalArgumentException(
                        "Перестановка вниз доступна только при автоматическом диапазоне");
            }
            if (maxDownwardReplacements <= 0) {
                throw new IllegalArgumentException(
                        "maxDownwardReplacements должен быть больше нуля для перестановки вниз");
            }
        }
    }

    /** Считает ли бот размер заявки от бюджета. */
    public boolean budgetSized() {
        return sizingMode != SizingMode.FIXED_LOTS;
    }

    /**
     * Деньги, которыми бот вправе распоряжаться на момент расчёта размера заявки.
     *
     * Ничего не мутирует: budget в конфигурации всегда остаётся той суммой, которую
     * задал пользователь, а реинвестирование выражается только этой формулой.
     *
     * @return null, если бюджет не задан (старые боты)
     */
    public BigDecimal workingBudget(BigDecimal realizedPnl) {
        if (budget == null) {
            return null;
        }
        return profitPolicy == ProfitPolicy.COMPOUND
                ? budget.add(realizedPnl == null ? BigDecimal.ZERO : realizedPnl)
                : budget;
    }

    /** Прибыль, выведенная из оборота бота. Только для отображения. */
    public BigDecimal withdrawnProfit(BigDecimal realizedPnl) {
        return profitPolicy == ProfitPolicy.WITHDRAW && realizedPnl != null
                ? realizedPnl
                : BigDecimal.ZERO;
    }
}
