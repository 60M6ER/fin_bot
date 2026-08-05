package ru.larionov.backend.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.larionov.backend.service.NotificationThrottle.Decision.*;

/**
 * Ограничитель уведомлений: на стриме событий на порядки больше, чем при поллинге,
 * и без него залипший в ошибке бот утопит в сообщениях то единственное,
 * ради чего уведомления и нужны.
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

    @Test
    void rateLimitStopsAStormOfDistinctMessages() {
        UUID bot = UUID.randomUUID();

        // Десять разных сообщений проходят...
        for (int i = 0; i < 10; i++) {
            assertThat(throttle.decide(bot, "ORDER_FILLED|уровень " + i))
                    .as("сообщение %d должно пройти", i)
                    .isEqualTo(SEND);
        }
        // ...одиннадцатое упирается в лимит частоты, хотя оно и не повтор.
        assertThat(throttle.decide(bot, "ORDER_FILLED|уровень 10")).isEqualTo(SUPPRESSED_RATE);
    }

    @Test
    void limitsAreCountedPerBotNotGlobally() {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();

        for (int i = 0; i < 12; i++) {
            throttle.decide(noisy, "ERROR|разное " + i);
        }
        // Шумный бот не должен затыкать соседнего.
        assertThat(throttle.decide(quiet, "ORDER_FILLED|первое сообщение")).isEqualTo(SEND);
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
                .contains("2 повтор");

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
