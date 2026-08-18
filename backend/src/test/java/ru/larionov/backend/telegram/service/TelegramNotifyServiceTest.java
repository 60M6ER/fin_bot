package ru.larionov.backend.telegram.service;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import ru.larionov.backend.telegram.config.TelegramSettings;
import ru.larionov.backend.telegram.repository.TelegramChatRepository;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramNotifyServiceTest {

    @Test
    void repeatedSendFailuresPauseFurtherDeliveryAttempts() throws Exception {
        NotificationsBot bot = mock(NotificationsBot.class);
        TelegramChatRepository chatRepo = mock(TelegramChatRepository.class);
        TelegramSettings settings = mock(TelegramSettings.class);
        when(settings.usable()).thenReturn(true);
        doThrow(new TelegramApiException("Read timed out")).when(bot).execute(any(SendMessage.class));

        TelegramNotifyService service = new TelegramNotifyService(bot, chatRepo, settings);

        service.sendToChat(181832419L, "one");
        service.sendToChat(181832419L, "two");
        service.sendToChat(181832419L, "three");
        service.sendToChat(181832419L, "four");

        verify(bot, times(3)).execute(any(SendMessage.class));
    }
}
