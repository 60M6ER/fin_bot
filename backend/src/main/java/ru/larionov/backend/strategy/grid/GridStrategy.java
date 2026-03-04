package ru.larionov.backend.strategy.grid;

import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.strategy.Strategy;
import ru.larionov.backend.strategy.StrategyContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class GridStrategy implements Strategy {

    private final GridConfig cfg;
    private StrategyContext ctx;
    private List<BigDecimal> grid;
    private long tick;

    public GridStrategy(GridConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void onStart(StrategyContext ctx) {
        this.ctx = ctx;

        if (!cfg.enabled()) {
            ctx.info("GRID: disabled by config");
            return;
        }

        this.grid = buildGrid(cfg.lowerPrice(), cfg.upperPrice(), cfg.levels());

        ctx.info("""
                GRID started
                instrument=%s
                conn=%s
                range=%s..%s
                levels=%d
                """.formatted(cfg.instrument(), cfg.exchangeConnectionId(), cfg.lowerPrice(), cfg.upperPrice(), cfg.levels()));

        log.info("GRID started for bot={}, instrument={}, levels={}", ctx.botId(), cfg.instrument(), cfg.levels());
    }

    @Override
    public void onTick() {
        if (ctx == null || grid == null) {
            return;
        }

        tick++;

        // NOTE: полноценная логика GRID (получение цены, постановка/снятие ордеров)
        // будет добавлена после того как согласуем минимальный контракт ExchangeClient
        // (lastPrice / placeLimit / cancel / activeOrders).
        if (tick % 50 == 0) {
            ctx.info("GRID tick=" + tick + ", levels=" + grid.size());
        }
    }

    @Override
    public void onStop() {
        if (ctx != null) {
            ctx.info("GRID stopped");
        }
        this.grid = null;
        this.ctx = null;
    }

    private static List<BigDecimal> buildGrid(BigDecimal low, BigDecimal high, int levels) {
        List<BigDecimal> res = new ArrayList<>(levels + 1);
        BigDecimal step = high.subtract(low).divide(BigDecimal.valueOf(levels), 8, RoundingMode.HALF_UP);
        for (int i = 0; i <= levels; i++) {
            res.add(low.add(step.multiply(BigDecimal.valueOf(i))));
        }
        return res;
    }
}
