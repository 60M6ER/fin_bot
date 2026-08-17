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
        /** Размер заявки в ЕДИНИЦАХ БАЗОВОГО АКТИВА. Дробный: у крипты это норма. */
        BigDecimal quantityPerOrder,
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
        ProfitPolicy profitPolicy,
        /*
         * Направление сетки. Лонг покупает ниже и продаёт выше, шорт — зеркально.
         * Умолчание LONG: конфигурация бота, заведённого до появления направления,
         * обязана означать ровно то же, что означала.
         */
        GridDirection direction,
        /*
         * Разрешена ли боту маржа. Второй уровень рубильника: первый — галка на
         * подключении, и бот не может включить то, чего подключение не разрешило.
         * Разделены намеренно — снятие галки на подключении обязано гасить всех
         * его ботов разом, не полагаясь на то, что каждый выключили по отдельности.
         */
        Boolean marginEnabled,
        /*
         * Сколько суток, по ожиданию, живёт один цикл сетки. Участвует ровно в одном
         * расчёте — в проверке, окупает ли шаг плату за перенос непокрытой позиции,
         * и только у шортовой сетки: длинная позиция покрыта и удержание её ничего
         * не стоит.
         *
         * Прогнозом это не является и являться не может: сколько цикл проживёт на
         * самом деле, зависит от рынка. Умолчание в сутки лишь не даёт забыть про
         * издержку целиком; кто рассчитывает на медленную сетку, обязан сказать это
         * явно и увидеть, во что она обходится.
         */
        Integer expectedCycleDays,
        /*
         * Что делать с позицией при неблагоприятном пробое: закрыть с убытком, как
         * было всегда, или перевернуть с множителем и отбивать убыток плечом.
         */
        AdverseBreakoutAction onAdverseBreakout,
        /** Множитель переворота. Тот самый ×4. */
        BigDecimal hedgeMultiplier,
        /*
         * Сколько эпизодов плеча разрешено на одно поколение.
         *
         * Единица по умолчанию, и это главный предохранитель всей конструкции.
         * Каждый следующий переворот умножает экспозицию: два подряд дают
         * девятикратную от исходной, три — двадцатисемикратную. Так проигрывают
         * счёт целиком за конечное число шагов, и «ещё разок» здесь — самое
         * дорогое решение из возможных.
         */
        Integer maxHedgeEpisodes,
        /** Сколько суток держим плечо, прежде чем закрыть по рынку и признать результат. */
        Integer maxHedgeHoldDays,
        /** Насколько цена вправе уйти против плеча, прежде чем эпизод закроется с убытком. */
        BigDecimal hedgeStopLossPct,
        /*
         * Работает ли новая сетка одновременно с плечом или ждёт его закрытия.
         * По умолчанию одновременно: смысл переворота в том, чтобы не простаивать.
         */
        Boolean hedgeAndGridConcurrent,
        /*
         * Переворачивать ли НАПРАВЛЕНИЕ САМОЙ СЕТКИ при неблагоприятном пробое.
         *
         * В этом и был замысел: пробили вниз — дальше торгуем падение шортом, пробили
         * вверх из шорта — возвращаемся в лонг. Без переворота новое поколение снова
         * встаёт лицом к тому движению, которое только что стоило денег, и покупает
         * в падение ровно так же, как покупало до пробоя.
         *
         * Требует маржи: перевернуться в шорт без неё нельзя, и у немаржинального
         * бота настройка не действует вовсе — там остаётся прежнее поведение.
         */
        Boolean flipDirectionOnAdverse,
        /*
         * Как эпизод плеча выходит: по расчётному безубытку или с храповиком,
         * который тянет цель за ценой. Умолчание — прежний безубыток.
         */
        HedgeExitMode hedgeExitMode,
        /*
         * Отступ храповика: доля цены, на которой подтянутая цель следует за ней.
         * Он же — величина отката, которую эпизод согласен отдать, прежде чем закрыться.
         *
         * Слишком тесный отступ выбьет эпизод на первом же шуме, слишком широкий
         * отдаст обратно всё движение. Действует только в режиме TARGET_WITH_TRAILING.
         */
        BigDecimal hedgeTrailingOffsetPct
) {

    /**
     * Чем заканчивается эпизод плеча при движении в нашу сторону.
     *
     * Разница не в арифметике, а в том, чем бот платит за шанс: BREAKEVEN_TARGET
     * держит заявку выхода в стакане и потому забирает безубыток даже между тиками,
     * но дальше цели не идёт. TARGET_WITH_TRAILING заявку не держит — иначе она
     * исполнилась бы ровно в тот момент, когда храповику полагалось начать работу, —
     * и закрывается по рынку на откате. Тем самым он меняет гарантию исполнения
     * на возможность взять больше безубытка.
     */
    public enum HedgeExitMode {
        /** Выход по расчётной цене безубытка. Прежнее и единственное поведение. */
        BREAKEVEN_TARGET,
        /** Цель едет за ценой в выгодную сторону и никогда обратно. */
        TARGET_WITH_TRAILING
    }

    public enum AdverseBreakoutAction {
        /** Закрыть позицию и зафиксировать убыток. Прежнее и единственное поведение. */
        LIQUIDATE,
        /** Перевернуть позицию с множителем и отбивать убыток плечом. */
        HEDGE_AND_RECOVER
    }

    public enum SizingMode {
        /**
         * Фиксированный размер из quantityPerOrder, бюджет не участвует.
         * Раньше назывался FIXED_LOTS; конфигурации существующих ботов переименованы
         * миграцией 701 — старое значение здесь не разберётся, и это правильно:
         * оно означало ЛОТЫ, то есть другое число.
         */
        FIXED_QUANTITY,
        /** Один размер на все уровни: бюджет / Σ цена_i, вниз до шага количества. */
        UNIFORM,
        /** Поровну денег на уровень: (бюджет/N) / цена_i, вниз до шага количества. */
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
                      BigDecimal quantityPerOrder,
                      Integer maxActiveOrders,
                      RangeExitAction onRangeExit,
                      BigDecimal minStepToCommissionRatio,
                      Boolean enabled) {
        this(lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders,
                onRangeExit, minStepToCommissionRatio, null, enabled,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /**
     * Конфигурация без маржинальных полей: лонговая сетка, как было до их появления.
     *
     * Существует ради тех, кому направление безразлично, — и ради того, чтобы
     * добавление маржинального поля не заставляло переписывать каждое место, где
     * конфигурация собирается позиционно. Умолчания здесь те же, что в компактном
     * конструкторе, так что смысл «как раньше» сохраняется буквально.
     */
    public GridConfig(BigDecimal lowerPrice, BigDecimal upperPrice, Integer levels,
                      BigDecimal quantityPerOrder, Integer maxActiveOrders,
                      RangeExitAction onRangeExit, BigDecimal minStepToCommissionRatio,
                      Integer feeRefreshSeconds, Boolean enabled, Boolean autoRange,
                      CandleInterval atrInterval, Integer atrPeriods, BigDecimal atrMultiplier,
                      BigDecimal minHalfWidthPct, BigDecimal maxHalfWidthPct,
                      UpperBreakoutAction onUpperBreakout, Integer breakoutConfirmSeconds,
                      BigDecimal breakoutMarginPct, Integer replaceCooldownSeconds,
                      Integer maxDownwardReplacements, BigDecimal maxRealizedLoss,
                      BigDecimal budget, SizingMode sizingMode, ProfitPolicy profitPolicy) {
        this(lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders, onRangeExit,
                minStepToCommissionRatio, feeRefreshSeconds, enabled, autoRange, atrInterval,
                atrPeriods, atrMultiplier, minHalfWidthPct, maxHalfWidthPct, onUpperBreakout,
                breakoutConfirmSeconds, breakoutMarginPct, replaceCooldownSeconds,
                maxDownwardReplacements, maxRealizedLoss, budget, sizingMode, profitPolicy,
                null, null, null);
    }

    /** Конфигурация без ожидаемой длительности цикла: заведённая до её появления. */
    public GridConfig(BigDecimal lowerPrice, BigDecimal upperPrice, Integer levels,
                      BigDecimal quantityPerOrder, Integer maxActiveOrders,
                      RangeExitAction onRangeExit, BigDecimal minStepToCommissionRatio,
                      Integer feeRefreshSeconds, Boolean enabled, Boolean autoRange,
                      CandleInterval atrInterval, Integer atrPeriods, BigDecimal atrMultiplier,
                      BigDecimal minHalfWidthPct, BigDecimal maxHalfWidthPct,
                      UpperBreakoutAction onUpperBreakout, Integer breakoutConfirmSeconds,
                      BigDecimal breakoutMarginPct, Integer replaceCooldownSeconds,
                      Integer maxDownwardReplacements, BigDecimal maxRealizedLoss,
                      BigDecimal budget, SizingMode sizingMode, ProfitPolicy profitPolicy,
                      GridDirection direction, Boolean marginEnabled) {
        this(lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders, onRangeExit,
                minStepToCommissionRatio, feeRefreshSeconds, enabled, autoRange, atrInterval,
                atrPeriods, atrMultiplier, minHalfWidthPct, maxHalfWidthPct, onUpperBreakout,
                breakoutConfirmSeconds, breakoutMarginPct, replaceCooldownSeconds,
                maxDownwardReplacements, maxRealizedLoss, budget, sizingMode, profitPolicy,
                direction, marginEnabled, null, null, null, null, null, null, null, null,
                null, null);
    }

    /** Конфигурация без восстановительного плеча: заведённая до его появления. */
    public GridConfig(BigDecimal lowerPrice, BigDecimal upperPrice, Integer levels,
                      BigDecimal quantityPerOrder, Integer maxActiveOrders,
                      RangeExitAction onRangeExit, BigDecimal minStepToCommissionRatio,
                      Integer feeRefreshSeconds, Boolean enabled, Boolean autoRange,
                      CandleInterval atrInterval, Integer atrPeriods, BigDecimal atrMultiplier,
                      BigDecimal minHalfWidthPct, BigDecimal maxHalfWidthPct,
                      UpperBreakoutAction onUpperBreakout, Integer breakoutConfirmSeconds,
                      BigDecimal breakoutMarginPct, Integer replaceCooldownSeconds,
                      Integer maxDownwardReplacements, BigDecimal maxRealizedLoss,
                      BigDecimal budget, SizingMode sizingMode, ProfitPolicy profitPolicy,
                      GridDirection direction, Boolean marginEnabled, Integer expectedCycleDays) {
        this(lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders, onRangeExit,
                minStepToCommissionRatio, feeRefreshSeconds, enabled, autoRange, atrInterval,
                atrPeriods, atrMultiplier, minHalfWidthPct, maxHalfWidthPct, onUpperBreakout,
                breakoutConfirmSeconds, breakoutMarginPct, replaceCooldownSeconds,
                maxDownwardReplacements, maxRealizedLoss, budget, sizingMode, profitPolicy,
                direction, marginEnabled, expectedCycleDays,
                null, null, null, null, null, null, null, null, null);
    }

    /**
     * Конфигурация без трейлинга: заведённая до его появления.
     *
     * Умолчание режима — BREAKEVEN_TARGET, поэтому такая конфигурация означает ровно
     * то же, что означала: выход по расчётному безубытку заявкой в стакане.
     */
    public GridConfig(BigDecimal lowerPrice, BigDecimal upperPrice, Integer levels,
                      BigDecimal quantityPerOrder, Integer maxActiveOrders,
                      RangeExitAction onRangeExit, BigDecimal minStepToCommissionRatio,
                      Integer feeRefreshSeconds, Boolean enabled, Boolean autoRange,
                      CandleInterval atrInterval, Integer atrPeriods, BigDecimal atrMultiplier,
                      BigDecimal minHalfWidthPct, BigDecimal maxHalfWidthPct,
                      UpperBreakoutAction onUpperBreakout, Integer breakoutConfirmSeconds,
                      BigDecimal breakoutMarginPct, Integer replaceCooldownSeconds,
                      Integer maxDownwardReplacements, BigDecimal maxRealizedLoss,
                      BigDecimal budget, SizingMode sizingMode, ProfitPolicy profitPolicy,
                      GridDirection direction, Boolean marginEnabled, Integer expectedCycleDays,
                      AdverseBreakoutAction onAdverseBreakout, BigDecimal hedgeMultiplier,
                      Integer maxHedgeEpisodes, Integer maxHedgeHoldDays,
                      BigDecimal hedgeStopLossPct, Boolean hedgeAndGridConcurrent,
                      Boolean flipDirectionOnAdverse) {
        this(lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders, onRangeExit,
                minStepToCommissionRatio, feeRefreshSeconds, enabled, autoRange, atrInterval,
                atrPeriods, atrMultiplier, minHalfWidthPct, maxHalfWidthPct, onUpperBreakout,
                breakoutConfirmSeconds, breakoutMarginPct, replaceCooldownSeconds,
                maxDownwardReplacements, maxRealizedLoss, budget, sizingMode, profitPolicy,
                direction, marginEnabled, expectedCycleDays, onAdverseBreakout, hedgeMultiplier,
                maxHedgeEpisodes, maxHedgeHoldDays, hedgeStopLossPct, hedgeAndGridConcurrent,
                flipDirectionOnAdverse, null, null);
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
        // quantityPerOrder и нет budget — они обязаны сохранить прежнее поведение до байта.
        if (sizingMode == null) {
            sizingMode = (budget == null) ? SizingMode.FIXED_QUANTITY : SizingMode.UNIFORM;
        }
        if (sizingMode == SizingMode.FIXED_QUANTITY) {
            if (quantityPerOrder == null || quantityPerOrder.signum() <= 0) {
                throw new IllegalArgumentException("quantityPerOrder обязателен и должен быть больше нуля");
            }
        } else if (budget == null || budget.signum() <= 0) {
            throw new IllegalArgumentException(
                    "budget обязателен и должен быть больше нуля, когда размер заявки считается от бюджета");
        }
        // quantityPerOrder в бюджетных режимах намеренно НЕ подставляем: любое забытое
        // чтение cfg.quantityPerOrder() должно упасть на старте, до единой заявки.
        // Подстановка «1» означала бы тихую торговлю одной штукой на реальные деньги.
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
        if (direction == null) {
            direction = GridDirection.LONG;
        }
        if (marginEnabled == null) {
            marginEnabled = false;
        }
        if (expectedCycleDays == null || expectedCycleDays <= 0) {
            expectedCycleDays = 1;
        }
        if (onAdverseBreakout == null) {
            onAdverseBreakout = AdverseBreakoutAction.LIQUIDATE;
        }
        if (hedgeMultiplier == null || hedgeMultiplier.compareTo(BigDecimal.ONE) <= 0) {
            hedgeMultiplier = new BigDecimal("4");
        }
        if (maxHedgeEpisodes == null || maxHedgeEpisodes < 0) {
            maxHedgeEpisodes = 1;
        }
        if (maxHedgeHoldDays == null || maxHedgeHoldDays <= 0) {
            maxHedgeHoldDays = 3;
        }
        if (hedgeAndGridConcurrent == null) {
            hedgeAndGridConcurrent = true;
        }
        // Умолчание — прежний выход по безубытку: бот, заведённый до трейлинга,
        // обязан вести эпизод ровно так же, как вёл.
        if (hedgeExitMode == null) {
            hedgeExitMode = HedgeExitMode.BREAKEVEN_TARGET;
        }
        // Полпроцента отката: достаточно, чтобы обычный шум не выбивал эпизод,
        // и достаточно мало, чтобы разворот не съел набранное.
        if (hedgeTrailingOffsetPct == null || hedgeTrailingOffsetPct.signum() <= 0) {
            hedgeTrailingOffsetPct = new BigDecimal("0.005");
        }
        // По умолчанию переворачиваем: маржинальную сетку заводят именно ради того,
        // чтобы торговать движение, а не стоять к нему спиной. У немаржинального
        // бота настройка всё равно не сработает — перевернуться ему некуда.
        if (flipDirectionOnAdverse == null) {
            flipDirectionOnAdverse = marginEnabled;
        }
        // Переворот открывает непокрытую позицию, а она невозможна без маржи —
        // и без потолка убытка, из которого считается срок и стоп.
        if (onAdverseBreakout == AdverseBreakoutAction.HEDGE_AND_RECOVER) {
            if (!marginEnabled) {
                throw new IllegalArgumentException(
                        "Восстановительное плечо требует включённой маржи");
            }
            if (maxRealizedLoss == null || maxRealizedLoss.signum() <= 0) {
                throw new IllegalArgumentException(
                        "Восстановительное плечо требует потолка убытка (maxRealizedLoss): "
                                + "без него у эпизода нет предела");
            }
        }
        // Шорт — это продажа того, чего нет. Без маржи такую заявку отвергнет и биржа,
        // и риск-контроль, поэтому конфигурация обязана падать здесь, на загрузке,
        // а не потоком отказов на каждом проходе торгового цикла.
        if (direction == GridDirection.SHORT && !marginEnabled) {
            throw new IllegalArgumentException(
                    "Шортовая сетка возможна только при включённой марже (marginEnabled)");
        }
    }

    /** Считает ли бот размер заявки от бюджета. */
    public boolean budgetSized() {
        return sizingMode != SizingMode.FIXED_QUANTITY;
    }

    /**
     * Копия с другим бюджетом — для команды оператора «изменить бюджет на лету».
     *
     * Копия, а не мутация: конфигурация проходит те же проверки конструктора, что и
     * при загрузке, и до успешной валидации сетки старая остаётся действующей. Иначе
     * отклонённое изменение оставило бы бота с полуприменённым бюджетом.
     */
    public GridConfig withBudget(BigDecimal newBudget) {
        return new GridConfig(
                lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders,
                onRangeExit, minStepToCommissionRatio, feeRefreshSeconds, enabled,
                autoRange, atrInterval, atrPeriods, atrMultiplier, minHalfWidthPct,
                maxHalfWidthPct, onUpperBreakout, breakoutConfirmSeconds, breakoutMarginPct,
                replaceCooldownSeconds, maxDownwardReplacements, maxRealizedLoss,
                newBudget, sizingMode, profitPolicy, direction, marginEnabled, expectedCycleDays,
                onAdverseBreakout, hedgeMultiplier, maxHedgeEpisodes, maxHedgeHoldDays,
                hedgeStopLossPct, hedgeAndGridConcurrent, flipDirectionOnAdverse,
                hedgeExitMode, hedgeTrailingOffsetPct);
    }

    /** Копия для проверки фактического направления текущего поколения. */
    public GridConfig withDirection(GridDirection newDirection) {
        return new GridConfig(
                lowerPrice, upperPrice, levels, quantityPerOrder, maxActiveOrders,
                onRangeExit, minStepToCommissionRatio, feeRefreshSeconds, enabled,
                autoRange, atrInterval, atrPeriods, atrMultiplier, minHalfWidthPct,
                maxHalfWidthPct, onUpperBreakout, breakoutConfirmSeconds, breakoutMarginPct,
                replaceCooldownSeconds, maxDownwardReplacements, maxRealizedLoss,
                budget, sizingMode, profitPolicy, newDirection, marginEnabled, expectedCycleDays,
                onAdverseBreakout, hedgeMultiplier, maxHedgeEpisodes, maxHedgeHoldDays,
                hedgeStopLossPct, hedgeAndGridConcurrent, flipDirectionOnAdverse,
                hedgeExitMode, hedgeTrailingOffsetPct);
    }

    /**
     * Деньги, которыми бот вправе распоряжаться на момент расчёта размера заявки.
     *
     * Ничего не мутирует: budget в конфигурации всегда остаётся той суммой, которую
     * задал пользователь, а реинвестирование выражается только этой формулой.
     *
     * @return null, если бюджет не задан (старые боты)
     */
    /**
     * Реализованный P/L спрашивается ЛЕНИВО — отсюда Supplier, а не значение.
     *
     * Он нужен только при реинвестировании прибыли, а достаётся дорого: это полная
     * загрузка денежной книги бота вместе с ремонтным проходом. Считать его для бота
     * с выводом прибыли (режим по умолчанию) — чистая трата на критическом пути запуска.
     *
     * Перегрузки со значением намеренно нет: {@code workingBudget(null)} был бы
     * двусмысленным, а такие ловушки потом стреляют.
     */
    public BigDecimal workingBudget(java.util.function.Supplier<BigDecimal> realizedPnl) {
        if (budget == null) {
            return null;
        }
        if (profitPolicy != ProfitPolicy.COMPOUND) {
            return budget;
        }
        BigDecimal pnl = realizedPnl.get();
        return budget.add(pnl == null ? BigDecimal.ZERO : pnl);
    }

    /** Прибыль, выведенная из оборота бота. Только для отображения. */
    public BigDecimal withdrawnProfit(BigDecimal realizedPnl) {
        return profitPolicy == ProfitPolicy.WITHDRAW && realizedPnl != null
                ? realizedPnl
                : BigDecimal.ZERO;
    }
}
