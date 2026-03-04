package ru.larionov.backend.strategy;

import ru.larionov.backend.exchange.api.ExchangeClient;

import java.util.UUID;

public interface StrategyContext {
    UUID botId();
    // Лог/уведомления (не напрямую)
    void info(String msg);
    void warn(String msg);
    void error(String msg, Throwable t);

    ExchangeClient exchange(UUID connectionId);
}
