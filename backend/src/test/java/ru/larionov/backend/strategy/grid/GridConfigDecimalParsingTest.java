package ru.larionov.backend.strategy.grid;

import org.junit.jupiter.api.Test;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Форма присылает дробное количество СТРОКОЙ — иначе double съедал бы последние
 * знаки у восьмизначных криптовеличин. Тест закрепляет, что бэкенд читает такую
 * строку в BigDecimal точно и не теряет ни одной цифры: на этом пути создаётся бот,
 * и молчаливая потеря знака означала бы заявку не того размера.
 */
class GridConfigDecimalParsingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void quantityArrivesAsStringAndKeepsEveryDigit() {
        String json = """
                {"instrumentUid":"POLONIEX:BTC_USDT","lowerPrice":"60000","upperPrice":"66000",
                 "levels":10,"quantityPerOrder":"0.00012345","sizingMode":"FIXED_QUANTITY"}
                """;

        GridConfig cfg = mapper.readValue(json, GridConfig.class);

        assertThat(cfg.quantityPerOrder()).isEqualByComparingTo("0.00012345");
        assertThat(cfg.quantityPerOrder().toPlainString())
                .as("цифры сохраняются ровно как набраны")
                .isEqualTo("0.00012345");
        assertThat(cfg.sizingMode()).isEqualTo(GridConfig.SizingMode.FIXED_QUANTITY);
    }

    @Test
    void quantityAlsoParsesWhenSentAsNumber() {
        String json = """
                {"lowerPrice":100,"upperPrice":110,"levels":10,"quantityPerOrder":5}
                """;

        assertThat(mapper.readValue(json, GridConfig.class).quantityPerOrder())
                .isEqualByComparingTo("5");
    }

    @Test
    void positionLimitParsesAsDecimal() {
        String json = """
                {"instrumentUid":"POLONIEX:BTC_USDT","maxPositionQuantity":"0.005"}
                """;

        assertThat(mapper.readValue(json, BotRuntimeConfig.class).maxPositionQuantity())
                .isEqualByComparingTo("0.005");
    }
}
