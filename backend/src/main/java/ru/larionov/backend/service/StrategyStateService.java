package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotStrategyStateEntity;
import ru.larionov.backend.repository.BotStrategyStateRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/** Типизированное чтение и атомарная замена контрольной точки стратегии. */
@Service
@RequiredArgsConstructor
public class StrategyStateService {

    private final BotStrategyStateRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public <T> Optional<T> read(UUID botId, Class<T> type) {
        return repo.findById(botId).map(entity -> {
            try {
                return objectMapper.readValue(entity.getState(), type);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Не удалось прочитать сохранённое состояние стратегии: " + e.getMessage(), e);
            }
        });
    }

    @Transactional
    public void write(UUID botId, Object state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            BotStrategyStateEntity entity = repo.findById(botId)
                    .orElseGet(() -> BotStrategyStateEntity.builder().botId(botId).build());
            entity.setState(json);
            repo.save(entity);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось сохранить состояние стратегии: " + e.getMessage(), e);
        }
    }
}
