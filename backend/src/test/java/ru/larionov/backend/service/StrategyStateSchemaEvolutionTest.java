package ru.larionov.backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.entity.BotStrategyStateEntity;
import ru.larionov.backend.repository.BotStrategyStateRepository;
import ru.larionov.backend.strategy.grid.GridStrategyState;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Контрольная точка стратегии обязана переживать развитие схемы.
 *
 * Инцидент 07.08.2026 на боевом сервере: в {@code GridStrategyState} появилось поле
 * {@code forcedReplacement}, а в сохранённых состояниях его не было. Примитивный
 * {@code boolean} без значения Jackson читать отказывается — и НИ ОДИН бот T-Invest
 * не поднялся: MAGN, RNFT и MVID падали на старте по кругу, супервизор перезапускал
 * их каждые 30 секунд с тем же результатом. Боты с позицией на бирже не могли ни
 * торговать, ни закрыться; спасло только удаление и пересоздание всех ботов.
 *
 * Цена ошибки несоразмерна причине: одно новое поле в состоянии = остановка всего парка.
 * Поэтому проверяется именно устойчивость к эволюции схемы, а не конкретное поле.
 */
@SpringBootTest
class StrategyStateSchemaEvolutionTest {

    @Autowired
    private StrategyStateService states;
    @Autowired
    private BotStrategyStateRepository repo;

    private final UUID botId = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        repo.deleteById(botId);
    }

    private void saveRaw(String json) {
        repo.save(BotStrategyStateEntity.builder().botId(botId).state(json).build());
    }

    /** Состояние ровно в том виде, в каком оно лежало в боевой базе до инцидента. */
    private static final String BEFORE_FORCED_REPLACEMENT = """
            {
              "activeRange": {
                "lower": 20.85375,
                "upper": 21.67625,
                "levels": 15,
                "origin": "ATR_INITIAL",
                "since": "2026-08-07T10:15:00Z"
              },
              "generation": 3,
              "awaitingUpperReplacement": false,
              "lastReplacementAt": null,
              "awaitingDownwardReplacement": false,
              "pendingRange": null,
              "downwardReplacements": 1,
              "realizedDownwardLoss": 12.5,
              "downwardLossBaseline": null
            }
            """;

    /**
     * Главный случай инцидента: поля нет, и это не повод не запускать бота.
     * Отсутствующий примитив обязан принять то же значение, что подставила бы
     * старая версия кода, которая про поле не знала.
     */
    @Test
    void stateWrittenBeforeTheFieldExistedIsStillReadable() {
        saveRaw(BEFORE_FORCED_REPLACEMENT);

        Optional<GridStrategyState> restored = states.read(botId, GridStrategyState.class);

        assertThat(restored).isPresent();
        GridStrategyState state = restored.orElseThrow();
        assertThat(state.forcedReplacement())
                .as("нет значения — значит ручной перестановки не было")
                .isFalse();
        assertThat(state.generation()).isEqualTo(3);
        assertThat(state.downwardReplacements()).isEqualTo(1);
        assertThat(state.realizedDownwardLoss()).isEqualByComparingTo("12.5");
        assertThat(state.activeRange().lower()).isEqualByComparingTo("20.85375");
        assertThat(state.activeRange().levels()).isEqualTo(15);
    }

    /** Явный null у примитива — то же самое, что отсутствие поля. */
    @Test
    void explicitNullForAPrimitiveIsTreatedAsTheDefault() {
        saveRaw(BEFORE_FORCED_REPLACEMENT.replace("\"downwardLossBaseline\": null",
                "\"downwardLossBaseline\": null, \"forcedReplacement\": null"));

        assertThat(states.read(botId, GridStrategyState.class).orElseThrow().forcedReplacement())
                .isFalse();
    }

    /**
     * Обратная сторона: состояние, записанное БОЛЕЕ НОВОЙ версией. Так выглядит откат
     * приложения на версию назад — незнакомое поле не должно ронять старт.
     */
    @Test
    void stateFromANewerVersionSurvivesARollback() {
        saveRaw(BEFORE_FORCED_REPLACEMENT.replace("\"generation\": 3",
                "\"generation\": 3, \"somethingAddedLater\": {\"nested\": [1, 2]}"));

        assertThat(states.read(botId, GridStrategyState.class).orElseThrow().generation())
                .isEqualTo(3);
    }

    /** Записанное новой версией читается новой же версией без потерь. */
    @Test
    void roundTripKeepsEveryField() {
        GridStrategyState written = new GridStrategyState(
                new ru.larionov.backend.strategy.grid.GridRange(
                        new BigDecimal("100"), new BigDecimal("110"), 10,
                        ru.larionov.backend.strategy.grid.GridRange.Origin.ATR_REPLACED_DOWN,
                        java.time.Instant.parse("2026-08-07T10:15:00Z")),
                7, true, java.time.Instant.parse("2026-08-07T11:00:00Z"),
                true, null, 2, new BigDecimal("3.5"), new BigDecimal("1.25"), true);

        states.write(botId, written);

        assertThat(states.read(botId, GridStrategyState.class).orElseThrow()).isEqualTo(written);
    }

    /**
     * Испорченное состояние по-прежнему роняет старт, и это правильно: подставить
     * боту с открытой позицией пустой диапазон — значит потерять цены встречных продаж.
     * Терпимость к схеме не должна превращаться в терпимость к мусору.
     */
    @Test
    void corruptedStateStillRefusesToLoad() {
        saveRaw("{\"activeRange\": \"это не диапазон\", \"generation\": 3}");

        assertThatThrownBy(() -> states.read(botId, GridStrategyState.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Не удалось прочитать сохранённое состояние стратегии");
    }
}
