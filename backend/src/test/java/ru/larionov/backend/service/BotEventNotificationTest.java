package ru.larionov.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import ru.larionov.backend.accounting.BotValuationService;
import ru.larionov.backend.dto.BotValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.enums.BotEventLevel;
import ru.larionov.backend.enums.BotEventType;
import ru.larionov.backend.repository.BotEventRepository;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.telegram.service.TelegramNotifyService;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Как выглядит уведомление, которое приходит в телефон.
 *
 * Смысл проверок: уведомление должно отвечать на вопрос «и что теперь» без похода
 * в интерфейс — сколько у бота денег и сколько он на этом заработал.
 */
class BotEventNotificationTest {

    private final UUID botId = UUID.randomUUID();

    private TelegramNotifyService notify;
    private BotValuationService valuation;
    private BotEventService events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        BotEventRepository repo = mock(BotEventRepository.class);
        BotRepository botRepo = mock(BotRepository.class);
        notify = mock(TelegramNotifyService.class);
        valuation = mock(BotValuationService.class);

        ObjectProvider<BotValuationService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(valuation);

        BotEntity bot = BotEntity.builder().id(botId).name("MAGN GRID").build();
        when(botRepo.findById(botId)).thenReturn(Optional.of(bot));

        events = new BotEventService(repo, botRepo, notify, provider, new ObjectMapper());
    }

    private BotValuationDto valued(String equity, String realized, String total) {
        return new BotValuationDto(
                false, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(realized), BigDecimal.ZERO,
                BigDecimal.ZERO, null, "RUB",
                null, null, null, null, total == null ? null : new BigDecimal(total),
                new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO,
                equity == null ? null : new BigDecimal(equity), "WITHDRAW", "UNIFORM");
    }

    private String captureBroadcast() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notify).broadcast(captor.capture());
        return captor.getValue();
    }

    @Test
    void botMessageCarriesItsBalanceAndPnlNextToTheName() {
        when(valuation.valuation(any())).thenReturn(valued("10250.5", "150", "250.5"));

        events.emit(botId, BotEventLevel.INFO, BotEventType.CYCLE_CLOSED,
                "Завершён цикл сетки на уровне 3. P/L цикла = +0,42 ₽");

        String text = captureBroadcast();
        assertThat(text)
                .contains("MAGN GRID")
                .contains("10 250,5 ₽")      // баланс
                .contains("+250,5 ₽")        // P/L с учётом позиции
                .contains("P/L цикла = +0,42 ₽");
    }

    /** Закрытие цикла — единственное событие, где сетка реально зарабатывает. */
    @Test
    void cycleClosedNowReachesTelegram() {
        when(valuation.valuation(any())).thenReturn(valued("10000", "0", "0"));

        events.emit(botId, BotEventLevel.INFO, BotEventType.CYCLE_CLOSED, "Завершён цикл сетки");

        verify(notify).broadcast(anyString());
    }

    /** Без цены рыночной оценки нет — показываем реализованный и честно это помечаем. */
    @Test
    void marksThePnlAsRealizedOnlyWhenThereIsNoPrice() {
        when(valuation.valuation(any())).thenReturn(valued(null, "150", null));

        events.emit(botId, BotEventLevel.INFO, BotEventType.CYCLE_CLOSED, "цикл");

        assertThat(captureBroadcast()).contains("+150 ₽ (реализ.)");
    }

    @Test
    void errorsGoOutAsTheirOwnMessage() {
        when(valuation.valuation(any())).thenReturn(valued("10000", "0", "0"));

        events.emit(botId, BotEventLevel.ERROR, BotEventType.ERROR, "Не удалось выставить заявку");

        // Склеенная с housekeeping-строками ошибка теряется среди них.
        verify(notify).broadcastIsolated(anyString());
        verify(notify, never()).broadcast(anyString());
    }

    @Test
    void ordinaryEventsAreAggregated() {
        when(valuation.valuation(any())).thenReturn(valued("10000", "0", "0"));

        events.emit(botId, BotEventLevel.INFO, BotEventType.ORDER_FILLED, "Куплено 9 лотов");

        verify(notify).broadcast(anyString());
        verify(notify, never()).broadcastIsolated(anyString());
    }

    /** Уведомление важнее украшений: сбой оценки не должен его съесть. */
    @Test
    void stillNotifiesWhenValuationFails() {
        when(valuation.valuation(any())).thenThrow(new IllegalStateException("книга недоступна"));

        events.emit(botId, BotEventLevel.INFO, BotEventType.ORDER_FILLED, "Куплено 9 лотов");

        assertThat(captureBroadcast()).contains("MAGN GRID").contains("Куплено 9 лотов");
    }

    @Test
    void housekeepingNeverReachesTelegram() {
        events.emit(botId, BotEventLevel.INFO, BotEventType.HOUSEKEEPING, "тик");

        verify(notify, never()).broadcast(anyString());
        verify(notify, never()).broadcastIsolated(anyString());
    }
}
