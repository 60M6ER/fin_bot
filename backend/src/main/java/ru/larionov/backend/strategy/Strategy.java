package ru.larionov.backend.strategy;

public interface Strategy {
    void onStart(StrategyContext ctx);
    void onStop();
    default void onTick() {
        // no-op by default
    }
}
