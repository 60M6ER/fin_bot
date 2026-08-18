package ru.larionov.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Сторож оборвавшихся стримов.
 *
 * 09.08.2026 стримы Poloniex оборвались, и оба бота простояли до тех пор, пока
 * подключение не перезапустили руками. Супервизор этого не замечал: он сверял
 * только «должно работать» против «работает», а подключение с мёртвым стримом
 * считается работающим — REST-вызовы у него живы.
 */
class RuntimeSupervisorStreamTest {

    private final UUID connectionId = UUID.randomUUID();

    private ExchangeConnectionRepository connectionRepo;
    private BotRepository botRepo;
    private ExchangeRuntimeService exchangeRuntime;
    private BotRuntimeService botRuntime;
    private ExchangeHandler handler;
    private RuntimeSupervisor supervisor;

    @BeforeEach
    void setUp() {
        connectionRepo = mock(ExchangeConnectionRepository.class);
        botRepo = mock(BotRepository.class);
        exchangeRuntime = mock(ExchangeRuntimeService.class);
        botRuntime = mock(BotRuntimeService.class);
        handler = mock(ExchangeHandler.class);

        ExchangeConnectionEntity conn = ExchangeConnectionEntity.builder()
                .id(connectionId)
                .name("Polonium")
                .exchange(ExchangeType.POLONIEX)
                .active(true)
                .build();

        when(connectionRepo.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(conn));
        when(botRepo.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of());
        when(botRepo.existsByExchangeConnectionIdAndActiveTrue(connectionId)).thenReturn(true);
        when(exchangeRuntime.isRunning(connectionId)).thenReturn(true);
        when(exchangeRuntime.get(connectionId)).thenReturn(Optional.of(handler));

        supervisor = new RuntimeSupervisor(connectionRepo, botRepo, exchangeRuntime, botRuntime);
    }

    @Test
    void deadMarketDataStreamRestartsTheConnection() {
        when(handler.marketDataStreamHealth()).thenReturn(
                new StreamHealth(false, Instant.now().minusSeconds(600), null, 1, "socket closed"));

        supervisor.reconcileDesiredState();

        verify(exchangeRuntime).restart(connectionId);
    }

    /**
     * Подключение может быть нужно само по себе: REST, баланс, UI. Если активных
     * ботов нет, market-data websocket не обязан быть подписан и подключен.
     */
    @Test
    void idleConnectionWithoutActiveBotsDoesNotRestartForMissingStream() {
        when(botRepo.existsByExchangeConnectionIdAndActiveTrue(connectionId)).thenReturn(false);
        when(handler.marketDataStreamHealth()).thenReturn(
                new StreamHealth(false, null, null, 0, null));

        supervisor.reconcileDesiredState();

        verify(exchangeRuntime, never()).restart(any());
    }

    /**
     * Молчание — не поломка. У брокера ночью не проходит ни одной сделки, и стрим
     * законно молчит часами: перезапуск по тишине означал бы перезапуск до утра.
     */
    @Test
    void aSilentButConnectedStreamIsLeftAlone() {
        when(handler.marketDataStreamHealth()).thenReturn(
                new StreamHealth(true, Instant.now().minusSeconds(36000), null, 0, null));

        supervisor.reconcileDesiredState();

        verify(exchangeRuntime, never()).restart(any());
    }

    /** Намерение пользователя сторож не трогает: это чинится техника, а не желание. */
    @Test
    void theWatchdogNeverDeactivatesTheConnection() {
        when(handler.marketDataStreamHealth()).thenReturn(
                new StreamHealth(false, null, null, 3, "socket closed"));

        supervisor.reconcileDesiredState();

        verify(exchangeRuntime, never()).deactivate(eq(connectionId));
    }

    @Test
    void successfulStreamRestartClearsBackoff() {
        when(handler.marketDataStreamHealth()).thenReturn(
                new StreamHealth(false, Instant.now().minusSeconds(600), null, 1, "socket closed"),
                new StreamHealth(true, Instant.now(), Instant.now(), 1, null),
                new StreamHealth(false, Instant.now().minusSeconds(600), null, 2, "socket closed"),
                new StreamHealth(true, Instant.now(), Instant.now(), 2, null));

        supervisor.reconcileDesiredState();
        supervisor.reconcileDesiredState();

        verify(exchangeRuntime, times(2)).restart(connectionId);
    }
}
