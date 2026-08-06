package ru.larionov.backend.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.larionov.backend.service.NotificationThrottle.Decision.*;

/**
 * Подавление повторов.
 *
 * Лимит частоты отсюда убран: он выбрасывал уведомления, а теперь поток склеивается
 * в одно сообщение и ничего не теряется. Дедупликация осталась — залипшая ошибка
 * повторяется часами, и склеивать сотню одинаковых строк так же бессмысленно,
 * как слать их по одной.
 */
class NotificationThrottleTest {

    private final NotificationThrottle throttle = new NotificationThrottle();

    @Test
    void firstMessageAlwaysGoesThrough() {
        assertThat(throttle.decide(UUID.randomUUID(), "ORDER_FILLED|куплено 1")).isEqualTo(SEND);
    }

    @Test
    void identicalMessageIsSuppressedAsDuplicate() {
        UUID bot = UUID.randomUUID();
        String key = "ERROR|таймаут биржи";

        assertThat(throttle.decide(bot, key)).isEqualTo(SEND);
        // Повторяющаяся из тика в тик ошибка — самый частый источник шума.
        assertThat(throttle.decide(bot, key)).isEqualTo(SUPPRESSED_DUPLICATE);
        assertThat(throttle.decide(bot, key)).isEqualTo(SUPPRESSED_DUPLICATE);
    }

    @Test
    void differentMessagesAreNotConfusedWithEachOther() {
        UUID bot = UUID.randomUUID();
        assertThat(throttle.decide(bot, "ORDER_FILLED|уровень 3")).isEqualTo(SEND);
        assertThat(throttle.decide(bot, "ORDER_FILLED|уровень 4")).isEqualTo(SEND);
        assertThat(throttle.decide(bot, "ORDER_PLACED|уровень 3")).isEqualTo(SEND);
    }

    /**
     * Регрессия на убранный лимит частоты: буря РАЗНЫХ событий больше не режется.
     * Раньше одиннадцатое сообщение за минуту выбрасывалось — терялись как раз
     * события бурного момента. Теперь их склеит агрегатор.
     */
    @Test
    void aStormOfDistinctMessagesIsNoLongerDropped() {
        UUID bot = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            assertThat(throttle.decide(bot, "ORDER_FILLED|уровень " + i))
                    .as("сообщение %d обязано пройти", i)
                    .isEqualTo(SEND);
        }
    }

    @Test
    void duplicatesAreCountedPerBotNotGlobally() {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        for (int i = 0; i < 12; i++) {
            throttle.decide(noisy, "ERROR|один и тот же текст");
        }
        // Шумный бот не должен затыкать соседнего.
        assertThat(throttle.decide(quiet, "ERROR|один и тот же текст")).isEqualTo(SEND);
    }

    @Test
    void summaryReportsWhatWasHiddenAndResetsAfterwards() {
        UUID bot = UUID.randomUUID();

        throttle.decide(bot, "ERROR|один и тот же текст");
        throttle.decide(bot, "ERROR|один и тот же текст");
        throttle.decide(bot, "ERROR|один и тот же текст");

        String summary = throttle.drainSummary(bot);
        assertThat(summary)
                .as("Тишина в Telegram не должна быть неотличима от отсутствия событий")
                .contains("Скрыто повторов: 2");

        // Сводка одноразовая: повторно то же самое не отправляем.
        assertThat(throttle.drainSummary(bot)).isNull();
    }

    @Test
    void summaryIsNullWhenNothingWasHidden() {
        UUID bot = UUID.randomUUID();
        throttle.decide(bot, "ORDER_FILLED|единственное");
        assertThat(throttle.drainSummary(bot)).isNull();
    }

    @Test
    void forgettingABotClearsItsCounters() {
        UUID bot = UUID.randomUUID();
        throttle.decide(bot, "ERROR|текст");
        throttle.decide(bot, "ERROR|текст");

        throttle.forget(bot);

        assertThat(throttle.knownBots()).doesNotContain(bot);
        // После забывания тот же текст снова проходит — состояния не осталось.
        assertThat(throttle.decide(bot, "ERROR|текст")).isEqualTo(SEND);
    }
}
