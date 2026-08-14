package ru.larionov.backend.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.exchange.api.model.CarryFeeSchedule;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.strategy.grid.GridConfig;
import ru.larionov.backend.strategy.grid.GridDirection;
import ru.larionov.backend.strategy.grid.GridLadder;
import ru.larionov.backend.strategy.grid.GridRange;
import ru.larionov.backend.strategy.grid.GridValidator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Плата за перенос непокрытой позиции.
 *
 * Шортов в системе ещё нет, поэтому проверяется синтетика: сама формула тарифа и то,
 * как она входит в проверку экономики сетки. Ждать появления шортов, чтобы проверить
 * арифметику издержки, — значит проверять её тогда, когда на ней уже стоят деньги.
 */
class CarryFeeTest {

    private static final BigDecimal DAILY = new BigDecimal("0.001");

    // ==============================
    // ТАРИФ
    // ==============================

    @Test
    @DisplayName("без ступеней действует ставка по умолчанию")
    void flatRateWithoutTiers() {
        CarryFeeSchedule schedule = new CarryFeeSchedule(List.of(), DAILY);

        assertThat(schedule.dailyCost(new BigDecimal("10000")))
                .isEqualByComparingTo("10");
        assertThat(schedule.costFor(new BigDecimal("10000"), 7))
                .isEqualByComparingTo("70");
    }

    /** Позиция тарифицируется по модулю: перенос платят за факт, а не за знак. */
    @Test
    @DisplayName("знак позиции на цену удержания не влияет")
    void signDoesNotChangeTheCost() {
        CarryFeeSchedule schedule = new CarryFeeSchedule(List.of(), DAILY);

        assertThat(schedule.dailyCost(new BigDecimal("-10000")))
                .isEqualByComparingTo(schedule.dailyCost(new BigDecimal("10000")));
    }

    @Test
    @DisplayName("ступень выбирается по размеру позиции")
    void tiersApplyByNotional() {
        CarryFeeSchedule schedule = new CarryFeeSchedule(List.of(
                new CarryFeeSchedule.Tier(new BigDecimal("50000"), new BigDecimal("0.002")),
                new CarryFeeSchedule.Tier(new BigDecimal("200000"), new BigDecimal("0.001"))
        ), new BigDecimal("0.0005"));

        assertThat(schedule.dailyRate(new BigDecimal("10000"))).isEqualByComparingTo("0.002");
        assertThat(schedule.dailyRate(new BigDecimal("100000"))).isEqualByComparingTo("0.001");
        // Выше последней ступени — ставка по умолчанию.
        assertThat(schedule.dailyRate(new BigDecimal("500000"))).isEqualByComparingTo("0.0005");
    }

    /**
     * Порядок ступеней задаёт сам тариф, а не тот, кто заполнял настройки.
     * Иначе переставленные строки молча меняли бы цену удержания.
     */
    @Test
    @DisplayName("перепутанные местами ступени дают тот же тариф")
    void tierOrderDoesNotMatter() {
        CarryFeeSchedule straight = new CarryFeeSchedule(List.of(
                new CarryFeeSchedule.Tier(new BigDecimal("50000"), new BigDecimal("0.002")),
                new CarryFeeSchedule.Tier(new BigDecimal("200000"), new BigDecimal("0.001"))
        ), DAILY);
        CarryFeeSchedule shuffled = new CarryFeeSchedule(List.of(
                new CarryFeeSchedule.Tier(new BigDecimal("200000"), new BigDecimal("0.001")),
                new CarryFeeSchedule.Tier(new BigDecimal("50000"), new BigDecimal("0.002"))
        ), DAILY);

        assertThat(shuffled.dailyRate(new BigDecimal("10000")))
                .isEqualByComparingTo(straight.dailyRate(new BigDecimal("10000")));
    }

    /** Забытый тариф обязан делать бота осторожнее, а не считать удержание бесплатным. */
    @Test
    @DisplayName("незаданный тариф подставляет консервативное умолчание")
    void missingScheduleFallsBackToDefault() {
        assertThat(ExchangeConnectionSettings.defaults().uncoveredCarryFee().defaultDailyRate())
                .isEqualByComparingTo(CarryFeeSchedule.DEFAULT_DAILY_RATE);
        assertThat(new CarryFeeSchedule(null, null).defaultDailyRate())
                .isEqualByComparingTo(CarryFeeSchedule.DEFAULT_DAILY_RATE);
    }

    /** Настройки, записанные до появления тарифа, читаются и получают умолчание. */
    @Test
    @DisplayName("старые настройки подключения не ломаются")
    void legacySettingsStillWork() {
        ExchangeConnectionSettings legacy = new ExchangeConnectionSettings(
                new BigDecimal("0.003"), null, null, null, "RUB");

        assertThat(legacy.marginEnabled()).isFalse();
        assertThat(legacy.uncoveredCarryFee()).isNotNull();
    }

    // ==============================
    // ЭКОНОМИКА СЕТКИ
    // ==============================

    /**
     * Лонговая сетка переноса не платит: длинная позиция покрыта.
     *
     * Проверяется не «числа сошлись», а что появление тарифа НИЧЕГО не изменило
     * для существующих ботов.
     */
    @Test
    @DisplayName("лонговой сетке перенос не добавляется")
    void longGridIgnoresCarry() {
        assertThatCode(() -> validate(GridDirection.LONG, 1, new BigDecimal("0.01")))
                .doesNotThrowAnyException();
    }

    /**
     * Шортовая сетка с однодневным циклом при той же ставке уже не проходит.
     *
     * Шаг здесь окупает комиссию с запасом, и без переноса сетка считалась бы годной.
     * Ровно так шортовая сетка и оказывалась бы убыточной незаметно.
     */
    @Test
    @DisplayName("шортовой сетке перенос ужесточает требование к шагу")
    void shortGridMustCoverCarry() {
        assertThatThrownBy(() -> validate(GridDirection.SHORT, 1, new BigDecimal("0.01")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("не окупает издержки")
                .hasMessageContaining("перенос за цикл");
    }

    /**
     * Чем дольше живёт цикл, тем больше он обязан окупать.
     *
     * Числа подобраны так, чтобы разница решала исход: шаг 1 на диапазоне 100..110
     * это 0.909% от верхней цены, комиссия с запасом требует 0.15%, а перенос по
     * 0.3% в сутки добавляет 0.3% за однодневный цикл и 1.2% за четырёхдневный.
     * Первый проходит, второй — нет, и разошлись они ровно на длительности.
     */
    @Test
    @DisplayName("долгий цикл требует ещё большего шага")
    void longerCycleRequiresBiggerStep() {
        assertThatCode(() -> validate(GridDirection.SHORT, 1, new BigDecimal("0.003")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validate(GridDirection.SHORT, 4, new BigDecimal("0.003")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("перенос за цикл");
    }

    /** Нулевая ставка означает «тариф не задан» и требование не меняет. */
    @Test
    @DisplayName("нулевая ставка переноса ничего не ужесточает")
    void zeroCarryChangesNothing() {
        assertThatCode(() -> validate(GridDirection.SHORT, 10, BigDecimal.ZERO))
                .doesNotThrowAnyException();
    }

    private void validate(GridDirection direction, int cycleDays, BigDecimal carryDailyRate) {
        GridConfig cfg = config(direction, cycleDays);
        GridRange range = new GridRange(new BigDecimal("100"), new BigDecimal("110"), 10,
                GridRange.Origin.MANUAL, Instant.EPOCH);
        GridLadder ladder = GridLadder.build(range, new BigDecimal("0.01"));

        GridValidator.validate(cfg, range, ladder, new BigDecimal("0.01"),
                new FeeInfo(new BigDecimal("0.0005"), new BigDecimal("0.0005")),
                BigDecimal.ONE, null, new BigDecimal("100000"), carryDailyRate);
    }

    private GridConfig config(GridDirection direction, int cycleDays) {
        return new GridConfig(
                new BigDecimal("100"), new BigDecimal("110"), 10, new BigDecimal("1"), 10,
                null, null, null, true, false,
                null, null, null, null, null,
                null, null, null, null, 0, null,
                null, GridConfig.SizingMode.FIXED_QUANTITY, null,
                direction, direction == GridDirection.SHORT, cycleDays);
    }
}
