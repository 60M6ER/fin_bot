package ru.larionov.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.repository.BotStrategyStateRepository;
import ru.larionov.backend.strategy.grid.GridRange;
import ru.larionov.backend.strategy.grid.GridStrategyState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StrategyStateServiceTest {

    private final UUID botId = UUID.randomUUID();

    @Autowired
    private StrategyStateService service;
    @Autowired
    private BotStrategyStateRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteById(botId);
    }

    @Test
    void gridRangeSurvivesJsonAndDatabaseRoundTrip() {
        GridStrategyState expected = new GridStrategyState(
                new GridRange(new BigDecimal("90.25"), new BigDecimal("110.75"), 12,
                        GridRange.Origin.ATR_INITIAL, Instant.parse("2026-08-05T00:00:00Z")),
                1);

        service.write(botId, expected);

        assertThat(service.read(botId, GridStrategyState.class)).contains(expected);
    }
}
