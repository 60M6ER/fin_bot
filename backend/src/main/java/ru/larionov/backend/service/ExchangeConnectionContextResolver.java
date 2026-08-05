package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.security.SecretCipher;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Превращает запись подключения в готовый к работе контекст: расшифровывает секреты
 * и разбирает jsonb-настройки.
 *
 * Вынесено отдельным компонентом, а не методом ExchangeConnectionService, чтобы не
 * создавать цикл: ExchangeConnectionService уже зависит от ExchangeRuntimeService
 * (ему нужен реальный runtime-статус), а ExchangeRuntimeService нужен этот резолвер.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExchangeConnectionContextResolver {

    private final ExchangeConnectionRepository repo;
    private final SecretCipher cipher;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ExchangeConnectionContext resolve(UUID id) {
        ExchangeConnectionEntity e = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));
        return resolve(e);
    }

    public ExchangeConnectionContext resolve(ExchangeConnectionEntity e) {
        return new ExchangeConnectionContext(
                e.getId(),
                e.getExchange(),
                e.getName(),
                cipher.decrypt(e.getApiKey()),
                cipher.decrypt(e.getApiSecret()),
                cipher.decrypt(e.getPassphrase()),
                e.isSandboxEnabled(),
                e.getAccountId(),
                parseSettings(e)
        );
    }

    public ExchangeConnectionSettings parseSettings(ExchangeConnectionEntity e) {
        String raw = e.getSettings();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return ExchangeConnectionSettings.defaults();
        }
        try {
            return objectMapper.readValue(raw, ExchangeConnectionSettings.class);
        } catch (Exception ex) {
            // Битые настройки не должны мешать подключению подняться: работаем на дефолтах,
            // но громко об этом сообщаем.
            log.warn("Некорректные settings у подключения {} ({}). Использую значения по умолчанию.",
                    e.getId(), ex.getMessage());
            return ExchangeConnectionSettings.defaults();
        }
    }
}
