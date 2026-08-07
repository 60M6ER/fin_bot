package ru.larionov.backend.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.MoneyLedgerRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class GridGenerationServiceTest {

    private final UUID botId = UUID.randomUUID();
    private final Instant t0 = Instant.parse("2026-02-01T10:00:00Z");
    private final BotExecutionContext ctx = new BotExecutionContext(
            botId, UUID.randomUUID(), new AccountId("acc-1"), new InstrumentId("uid-1", null),
            false, BigDecimal.ONE, BigDecimal.ONE, null, null, null, null, null);

    @Autowired
    private GridGenerationService generations;
    @Autowired
    private MoneyLedgerRepository ledgerRepo;

    @AfterEach
    void cleanUp() {
        generations.deleteAllForBot(botId);
        ledgerRepo.deleteAll(ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, false));
    }

    /**
     * Основной сценарий: сетка отработала циклы, пробой вниз закрыл позицию с убытком,
     * и этот убыток становится ценой входа в следующее поколение.
     */
    @Test
    void downwardReplacementChargesLiquidationLossToTheNewGeneration() {
        assertThat(open(1, "90", "110", "ATR_INITIAL", t0)).isEmpty();

        cycle(0, "5.00");
        cycle(1, "7.00");
        liquidation("-20.00");

        Optional<GridGenerationDto> closed =
                open(2, "80", "100", "ATR_REPLACED_DOWN", t0.plusSeconds(3600));

        assertThat(closed).isPresent();
        assertThat(closed.get().generation()).isEqualTo(1);
        assertThat(closed.get().cycles()).isEqualTo(2);
        assertThat(closed.get().cyclesPnl()).isEqualByComparingTo("12.00");
        assertThat(closed.get().transitionCost())
                .as("В первый диапазон бот вошёл бесплатно")
                .isEqualByComparingTo("0");
        assertThat(closed.get().totalPnl()).isEqualByComparingTo("12.00");
        assertThat(closed.get().active()).isFalse();

        cycle(0, "3.00");
        List<GridGenerationDto> list = generations.list(botId, false);

        assertThat(list).hasSize(2);
        GridGenerationDto current = list.get(0);
        assertThat(current.generation()).as("Свежее поколение идёт первым").isEqualTo(2);
        assertThat(current.active()).isTrue();
        assertThat(current.cycles()).isEqualTo(1);
        assertThat(current.cyclesPnl()).isEqualByComparingTo("3.00");
        assertThat(current.transitionCost())
                .as("Убыток ликвидации — это цена входа в новый диапазон")
                .isEqualByComparingTo("-20.00");
        assertThat(current.totalPnl()).isEqualByComparingTo("-17.00");

        assertThat(list.get(1).cycles())
                .as("Ликвидационная продажа не считается циклом закрытого поколения")
                .isEqualTo(2);
    }

    /** Рестарт бота внутри поколения не должен дробить его историю. */
    @Test
    void repeatedOpenOfTheSameGenerationChangesNothing() {
        open(1, "90", "110", "ATR_INITIAL", t0);
        cycle(0, "4.00");

        assertThat(open(1, "90", "110", "ATR_INITIAL", t0.plusSeconds(60))).isEmpty();

        List<GridGenerationDto> list = generations.list(botId, false);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).cycles()).isEqualTo(1);
        assertThat(list.get(0).startedAt()).isEqualTo(t0);
    }

    /**
     * Учёт поколений могли включить на боте, который торгует давно: его прошлые
     * циклы обязаны попасть в первое поколение, а не пропасть.
     */
    @Test
    void firstGenerationPicksUpHistoryRecordedBeforeIt() {
        cycle(0, "9.00");

        open(1, "90", "110", "ATR_INITIAL", t0);

        assertThat(generations.list(botId, false).get(0).cyclesPnl()).isEqualByComparingTo("9.00");
    }

    private Optional<GridGenerationDto> open(long generation, String lower, String upper,
                                             String origin, Instant at) {
        return generations.roll(ctx, generation, new BigDecimal(lower), new BigDecimal(upper),
                4, origin, at);
    }

    private void cycle(Integer gridLevel, String amount) {
        saveCycleResult(gridLevel, amount);
    }

    /** Принудительное закрытие позиции: одна продажа без уровня сетки. */
    private void liquidation(String amount) {
        saveCycleResult(null, amount);
    }

    private void saveCycleResult(Integer gridLevel, String amount) {
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.CYCLE_RESULT)
                .affectsCash(false)
                .orderId(UUID.randomUUID())
                .side(OrderSide.SELL)
                .gridLevel(gridLevel)
                .quantity(BigDecimal.ONE)
                .exchangeLotSize(BigDecimal.ONE)
                .amount(new BigDecimal(amount))
                .executedQuantityCum(BigDecimal.ONE)
                .currency("rub")
                .build());
    }
}
