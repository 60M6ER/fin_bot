package ru.larionov.backend.accounting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.exchange.api.enums.OrderSide;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.MoneyLedgerRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Восстановительный эпизод отдельной строкой в отчёте по поколениям.
 *
 * Главный риск здесь — двойной счёт. Плечо работает ОДНОВРЕМЕННО с сеткой, значит
 * их окна в книге пересекаются, и одного окна для разделения денег уже мало: без
 * дополнительного признака один и тот же результат попал бы и в поколение, и в
 * эпизод. Ошибка такого рода не падает — она просто удваивает прибыль в отчёте.
 */
@SpringBootTest
class GridGenerationRecoveryRowTest {

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

    @Test
    @DisplayName("деньги сетки и плеча не смешиваются, хотя окна пересекаются")
    void gridAndHedgeMoneyStaySeparate() {
        generations.roll(ctx, 1, new BigDecimal("90"), new BigDecimal("110"), 4, "ATR_INITIAL", t0);

        UUID episodeId = UUID.randomUUID();
        generations.openRecovery(ctx, 1, episodeId, "SHORT",
                new BigDecimal("100"), new BigDecimal("96"), new BigDecimal("4"), t0.plusSeconds(60));

        // Дальше обе ноги работают одновременно, и их результаты идут вперемешку.
        cycleResult(OrderPurpose.GRID, 3, "5.00");
        cycleResult(OrderPurpose.RECOVERY, null, "40.00");
        cycleResult(OrderPurpose.GRID, 4, "7.00");

        List<GridGenerationDto> rows = generations.list(botId, false);
        GridGenerationDto grid = rowOfKind(rows, "GRID");
        GridGenerationDto recovery = rowOfKind(rows, "RECOVERY");

        assertThat(grid.cyclesPnl())
                .as("поколению принадлежат только его циклы")
                .isEqualByComparingTo("12.00");
        assertThat(grid.cycles()).isEqualTo(2);

        assertThat(recovery.cyclesPnl())
                .as("эпизоду — только его результат")
                .isEqualByComparingTo("40.00");
        assertThat(recovery.cycles()).isEqualTo(1);

        assertThat(grid.cyclesPnl().add(recovery.cyclesPnl()))
                .as("вместе они дают ровно то, что записано в книге, — без удвоения")
                .isEqualByComparingTo("52.00");
    }

    /**
     * Стоимость перехода — свойство сеточной цепочки. Эпизод в ней не участвует,
     * иначе он унёс бы чужую стоимость и не отдал следующему поколению.
     */
    @Test
    @DisplayName("эпизод не вмешивается в цепочку стоимости перехода")
    void recoveryDoesNotBreakTheTransitionChain() {
        generations.roll(ctx, 1, new BigDecimal("90"), new BigDecimal("110"), 4, "ATR_INITIAL", t0);
        generations.openRecovery(ctx, 1, UUID.randomUUID(), "SHORT",
                new BigDecimal("100"), new BigDecimal("96"), new BigDecimal("4"), t0.plusSeconds(60));

        cycleResult(OrderPurpose.LIQUIDATION, null, "-20.00");
        generations.roll(ctx, 2, new BigDecimal("80"), new BigDecimal("100"), 4,
                "ATR_REPLACED_DOWN", t0.plusSeconds(3600));

        List<GridGenerationDto> rows = generations.list(botId, false);
        GridGenerationDto second = rows.stream()
                .filter(r -> r.generation() == 2 && "GRID".equals(r.kind()))
                .findFirst().orElseThrow();
        GridGenerationDto recovery = rowOfKind(rows, "RECOVERY");

        assertThat(second.transitionCost())
                .as("убыток ликвидации обязан достаться следующему поколению, а не эпизоду")
                .isEqualByComparingTo("-20.00");
        assertThat(recovery.transitionCost())
                .as("у эпизода стоимости перехода нет вовсе")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("строка эпизода несёт свои цены и закрывается по завершении")
    void recoveryRowCarriesItsPricesAndCloses() {
        generations.roll(ctx, 1, new BigDecimal("90"), new BigDecimal("110"), 4, "ATR_INITIAL", t0);
        UUID episodeId = UUID.randomUUID();
        generations.openRecovery(ctx, 1, episodeId, "SHORT",
                new BigDecimal("100"), new BigDecimal("96"), new BigDecimal("4"), t0.plusSeconds(60));

        GridGenerationDto open = rowOfKind(generations.list(botId, false), "RECOVERY");
        assertThat(open.active()).isTrue();
        assertThat(open.entryPrice()).isEqualByComparingTo("100");
        assertThat(open.targetPrice()).isEqualByComparingTo("96");
        assertThat(open.multiplier()).isEqualByComparingTo("4");
        assertThat(open.margin()).isTrue();
        assertThat(open.direction()).isEqualTo("SHORT");

        generations.closeRecovery(ctx, episodeId, t0.plusSeconds(7200));

        assertThat(rowOfKind(generations.list(botId, false), "RECOVERY").active()).isFalse();
    }

    /** Повторное открытие того же эпизода не должно плодить строки. */
    @Test
    @DisplayName("открытие эпизода идемпотентно")
    void openingTheSameEpisodeTwiceIsIdempotent() {
        generations.roll(ctx, 1, new BigDecimal("90"), new BigDecimal("110"), 4, "ATR_INITIAL", t0);
        UUID episodeId = UUID.randomUUID();

        generations.openRecovery(ctx, 1, episodeId, "SHORT",
                new BigDecimal("100"), new BigDecimal("96"), new BigDecimal("4"), t0);
        generations.openRecovery(ctx, 1, episodeId, "SHORT",
                new BigDecimal("100"), new BigDecimal("96"), new BigDecimal("4"), t0);

        assertThat(generations.list(botId, false).stream()
                .filter(r -> "RECOVERY".equals(r.kind())).count()).isEqualTo(1);
    }

    private GridGenerationDto rowOfKind(List<GridGenerationDto> rows, String kind) {
        return rows.stream().filter(r -> kind.equals(r.kind())).findFirst().orElseThrow();
    }

    private void cycleResult(OrderPurpose purpose, Integer gridLevel, String amount) {
        ledgerRepo.save(MoneyLedgerEntity.builder()
                .botId(botId)
                .dryRun(false)
                .entryType(LedgerEntryType.CYCLE_RESULT)
                .affectsCash(false)
                .orderId(UUID.randomUUID())
                .side(OrderSide.SELL)
                .purpose(purpose)
                .gridLevel(gridLevel)
                .quantity(BigDecimal.ONE)
                .exchangeLotSize(BigDecimal.ONE)
                .amount(new BigDecimal(amount))
                .executedQuantityCum(BigDecimal.ONE)
                .currency("RUB")
                .build());
    }
}
