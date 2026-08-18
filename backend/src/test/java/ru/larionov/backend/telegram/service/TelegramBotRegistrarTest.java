package ru.larionov.backend.telegram.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.larionov.backend.telegram.config.TelegramSettings;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramBotRegistrarTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void failedRegistrationDoesNotEscape() throws Exception {
        TelegramBotsApi api = mock(TelegramBotsApi.class);
        NotificationsBot bot = mock(NotificationsBot.class);
        TelegramSettings settings = mock(TelegramSettings.class);
        doThrow(new TelegramApiException("network is down")).when(api).registerBot(bot);

        TelegramBotRegistrar registrar = new TelegramBotRegistrar(api, bot, settings,
                Executors.newSingleThreadExecutor());

        assertThatCode(registrar::registerSafely).doesNotThrowAnyException();

        verify(settings, never()).markRegistered();
        registrar.shutdown();
    }

    @Test
    void registrationRunsAfterStartupWithoutBlockingTheCaller() throws Exception {
        TelegramBotsApi api = mock(TelegramBotsApi.class);
        NotificationsBot bot = mock(NotificationsBot.class);
        TelegramSettings settings = mock(TelegramSettings.class);
        when(settings.usable()).thenReturn(true);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        }).when(api).registerBot(bot);

        executor = Executors.newSingleThreadExecutor();
        TelegramBotRegistrar registrar = new TelegramBotRegistrar(api, bot, settings, executor);

        long startedAt = System.nanoTime();
        registrar.registerAfterStartup();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(elapsedMs).isLessThan(200);
        assertThat(entered.await(Duration.ofSeconds(2).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS))
                .isTrue();

        release.countDown();
        registrar.shutdown();
    }
}
