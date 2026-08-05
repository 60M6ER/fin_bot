package ru.larionov.backend.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.strategy.grid.GridConfig;
import ru.larionov.backend.strategy.grid.GridStrategy;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class DefaultStrategyFactory implements StrategyFactory {

    private final ObjectMapper om;

    @Override
    public Strategy create(BotEntity bot) {
        StrategyType type = bot.getStrategyType();
        if (type == null) {
            return new NoopStrategy();
        }

        return switch (type) {
            case GRID -> new GridStrategy(read(bot.getStrategyConfig(), GridConfig.class));
            case NONE -> new NoopStrategy();
            default -> new NoopStrategy(); // пока заглушки
        };
    }

    private <T> T read(String json, Class<T> clazz) {
        try {
            return om.readValue(json == null ? "{}" : json, clazz);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid strategy config for " + clazz.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
