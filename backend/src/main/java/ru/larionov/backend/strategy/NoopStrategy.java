package ru.larionov.backend.strategy;

import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.StrategyContext;

public class NoopStrategy implements Strategy {
    @Override public void onStart(StrategyContext ctx) { ctx.info("Strategy: NONE"); }
    @Override public void onStop() { }
}
