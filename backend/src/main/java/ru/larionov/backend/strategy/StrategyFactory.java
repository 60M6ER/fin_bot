package ru.larionov.backend.strategy;

import ru.larionov.backend.entity.BotEntity;

public interface StrategyFactory {
    Strategy create(BotEntity bot);
}
