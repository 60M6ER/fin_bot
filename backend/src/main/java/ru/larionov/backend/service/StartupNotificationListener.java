package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.larionov.backend.telegram.service.TelegramNotifyService;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupNotificationListener {

    private final TelegramNotifyService notifyService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {

        String message = """
                🚀 Сервер запущен
                                
                Время старта: %s
                Профиль: %s
                """.formatted(
                Instant.now(),
                System.getProperty("spring.profiles.active", "default")
        );

        notifyService.broadcast(message);
        log.info("Startup telegram notification sent");
    }
}
