package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.entity.GridGenerationEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.GenerationKind;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.enums.OrderPurpose;
import ru.larionov.backend.execution.BotExecutionContext;
import ru.larionov.backend.repository.GridGenerationRepository;
import ru.larionov.backend.repository.MoneyLedgerRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Статистика по поколениям сетки: успешность каждого диапазона по отдельности.
 *
 * Хранится здесь ровно одно — границы поколения. Все деньги считаются из книги
 * операций по окну seq, и это принципиально: {@code AccountingService} уточняет
 * суммы уже записанных строк (комиссия приходит от биржи позже сделки), поэтому
 * снимок, сделанный в момент перестановки, начал бы расходиться с книгой в тот же
 * день. Здесь расхождение невозможно по построению.
 *
 * <h3>Кому принадлежит убыток ликвидации</h3>
 * Принудительное закрытие позиции при пробое вниз происходит ещё в СТАРОМ поколении:
 * его продажи попадают в старое окно. Но платим мы этот убыток за вход в новый
 * диапазон, поэтому в отчёте он показывается стоимостью перехода СЛЕДУЮЩЕГО
 * поколения. Отличить эти продажи от обычных циклов просто: ликвидация закрывает
 * позицию целиком одной заявкой без уровня сетки.
 */
@Service
@RequiredArgsConstructor
public class GridGenerationService {

    private final GridGenerationRepository repo;
    private final MoneyLedgerRepository ledgerRepo;

    /** Лонговая сетка без плеча — режим по умолчанию и единственный до его появления. */
    @Transactional
    public Optional<GridGenerationDto> roll(BotExecutionContext ctx,
                                            long generation,
                                            BigDecimal lowerPrice,
                                            BigDecimal upperPrice,
                                            Integer levels,
                                            String origin,
                                            Instant startedAt) {
        return roll(ctx, generation, lowerPrice, upperPrice, levels, origin, startedAt, "LONG", false);
    }

    /**
     * Открывает новое поколение и закрывает предыдущее.
     *
     * Идемпотентна: рестарт бота внутри того же поколения ничего не меняет и ничего
     * не возвращает — иначе каждый перезапуск дробил бы историю на огрызки.
     *
     * @return итог закрытого поколения, если оно было
     */
    @Transactional
    public Optional<GridGenerationDto> roll(BotExecutionContext ctx,
                                            long generation,
                                            BigDecimal lowerPrice,
                                            BigDecimal upperPrice,
                                            Integer levels,
                                            String origin,
                                            Instant startedAt,
                                            String direction,
                                            boolean margin) {
        UUID botId = ctx.botId();
        boolean dryRun = ctx.dryRun();

        List<GridGenerationEntity> rows = repo.findAllByBotIdAndDryRunOrderByGenerationAsc(botId, dryRun);
        // Только сеточные строки: у одного поколения законно бывает и восстановительный
        // эпизод, и он не должен выглядеть как «поколение уже открыто».
        if (rows.stream().anyMatch(row -> row.getGeneration() == generation
                && row.getKind() == GenerationKind.GRID)) {
            return Optional.empty();
        }

        long boundary = ledgerRepo.maxSeq(botId, dryRun);
        Instant now = startedAt == null ? Instant.now() : startedAt;

        GridGenerationEntity closed = null;
        for (GridGenerationEntity row : rows) {
            if (row.getEndedAt() == null) {
                row.setEndedAt(now);
                row.setEndSeq(boundary);
                closed = row;
            }
        }
        repo.saveAll(rows);

        // Первое поколение бота получает всю книгу целиком: учёт поколений могли
        // включить на боте, который торгует давно, и его история — не пустое место.
        repo.save(GridGenerationEntity.builder()
                .botId(botId)
                .dryRun(dryRun)
                .generation(generation)
                .lowerPrice(lowerPrice)
                .upperPrice(upperPrice)
                .levels(levels)
                .origin(origin)
                .kind(GenerationKind.GRID)
                .direction(direction == null ? "LONG" : direction)
                .margin(margin)
                .startedAt(now)
                .startSeq(rows.isEmpty() ? 0L : boundary)
                .build());

        if (closed == null) {
            return Optional.empty();
        }
        long closedGeneration = closed.getGeneration();
        return build(botId, dryRun).stream()
                .filter(dto -> dto.generation() == closedGeneration)
                .findFirst();
    }

    /** Поколения бота от свежего к старому — в таком порядке их и читают. */
    @Transactional(readOnly = true)
    public List<GridGenerationDto> list(UUID botId, boolean dryRun) {
        List<GridGenerationDto> result = new ArrayList<>(build(botId, dryRun));
        Collections.reverse(result);
        return result;
    }

    @Transactional
    public void deleteAllForBot(UUID botId) {
        repo.deleteAllByBotId(botId);
    }

    /**
     * Считает поколения по возрастанию: стоимость перехода поколения — это убыток
     * ликвидации предыдущего, и получить её можно только двигаясь по порядку.
     */
    private List<GridGenerationDto> build(UUID botId, boolean dryRun) {
        List<GridGenerationEntity> rows = repo.findAllByBotIdAndDryRunOrderByGenerationAsc(botId, dryRun);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<MoneyLedgerEntity> ledger = ledgerRepo.findAllByBotIdAndDryRunOrderBySeqAsc(botId, dryRun);
        List<GridGenerationDto> result = new ArrayList<>(rows.size());

        // Во сколько обошёлся вход в очередное поколение. Ноль у самого первого:
        // за первый диапазон бот ничем не платил.
        BigDecimal transitionCost = BigDecimal.ZERO;

        for (GridGenerationEntity row : rows) {
            Aggregate window = aggregate(ledger, row);
            boolean recovery = row.getKind() == GenerationKind.RECOVERY;
            // Стоимость перехода — свойство СЕТОЧНОЙ цепочки: она передаётся от одного
            // диапазона к следующему. Эпизод плеча в этой цепочке не участвует вовсе,
            // иначе он унёс бы чужую стоимость перехода и не отдал её следующему
            // поколению — а у того она обнулилась бы молча.
            BigDecimal rowTransitionCost = recovery ? BigDecimal.ZERO : transitionCost;
            result.add(new GridGenerationDto(
                    row.getGeneration(),
                    row.getLowerPrice(),
                    row.getUpperPrice(),
                    row.getLevels(),
                    row.getOrigin(),
                    row.getStartedAt(),
                    row.getEndedAt(),
                    row.getEndedAt() == null,
                    rowTransitionCost,
                    window.cycles,
                    window.cyclesPnl,
                    rowTransitionCost.add(window.cyclesPnl),
                    window.currency,
                    row.getKind() == null ? "GRID" : row.getKind().name(),
                    row.getDirection(),
                    row.isMargin(),
                    row.getEntryPrice(),
                    row.getTargetPrice(),
                    row.getMultiplier()
            ));
            if (!recovery) {
                transitionCost = window.liquidationPnl;
            }
        }
        return result;
    }

    private Aggregate aggregate(List<MoneyLedgerEntity> ledger, GridGenerationEntity generation) {
        Aggregate aggregate = new Aggregate();
        boolean recovery = generation.getKind() == GenerationKind.RECOVERY;

        for (MoneyLedgerEntity row : ledger) {
            if (row.getSeq() == null
                    || row.getSeq() <= generation.getStartSeq()
                    || (generation.getEndSeq() != null && row.getSeq() > generation.getEndSeq())) {
                continue;
            }
            /*
             * Окна строк ПЕРЕСЕКАЮТСЯ, когда плечо работает одновременно с сеткой,
             * и одного окна для разделения денег уже мало: без этой проверки один
             * и тот же результат попал бы и в поколение, и в эпизод, то есть был бы
             * посчитан дважды.
             *
             * Признак принадлежности — назначение заявки. Строки плеча принадлежат
             * восстановительной строке, все остальные — сеточной.
             */
            if (isHedgeRow(row) != recovery) {
                continue;
            }
            if (aggregate.currency == null && row.getCurrency() != null) {
                aggregate.currency = row.getCurrency();
            }
            if (row.getEntryType() != LedgerEntryType.CYCLE_RESULT) {
                continue;
            }
            if (recovery) {
                // У эпизода нет уровней и нет «стоимости перехода»: весь его результат —
                // это то, чем закончилось плечо.
                aggregate.cycles++;
                aggregate.cyclesPnl = aggregate.cyclesPnl.add(
                        row.getAmount() == null ? BigDecimal.ZERO : row.getAmount());
                continue;
            }

            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            // Назначение решает первым, отсутствие уровня — только как запасное правило.
            //
            // Отличать ликвидацию от цикла по «уровень не задан» можно было, пока заявка
            // без уровня была ровно одна. Их уже две (добавилась продажа пыли), а с
            // восстановительным плечом станет больше — и эвристика начала бы записывать
            // чужие деньги в стоимость перехода молча. Ровно об этом классе ошибок
            // предупреждает javadoc OrderPurpose.
            //
            // Запасное правило оставлено намеренно и убирать его нельзя: у строк,
            // записанных до появления колонки purpose, миграция проставила GRID всем
            // подряд. Спроси мы только назначение — стоимость перехода всех старых
            // поколений тихо обнулилась бы, и отчёт соврал бы именно про то, ради чего
            // его читают.
            boolean liquidation = row.getPurpose() == OrderPurpose.LIQUIDATION
                    || row.getGridLevel() == null;
            if (liquidation) {
                // Принудительное закрытие позиции перед перестановкой: это не цикл
                // сетки, а цена выхода из неё.
                aggregate.liquidationPnl = aggregate.liquidationPnl.add(amount);
            } else {
                aggregate.cycles++;
                aggregate.cyclesPnl = aggregate.cyclesPnl.add(amount);
            }
        }
        return aggregate;
    }

    /** Строка книги принадлежит восстановительному плечу, а не сетке. */
    private static boolean isHedgeRow(MoneyLedgerEntity row) {
        return row.getPurpose() == OrderPurpose.HEDGE || row.getPurpose() == OrderPurpose.RECOVERY;
    }

    /**
     * Открывает строку восстановительного эпизода.
     *
     * Со своим окном книги, поэтому деньги эпизода считаются тем же механизмом, что
     * и деньги поколения, — и уточнение комиссий доезжает до отчёта само собой.
     * Поколение при этом НЕ закрывается: плечо и сетка живут одновременно.
     */
    @Transactional
    public void openRecovery(BotExecutionContext ctx, long generation, UUID episodeId,
                             String direction, BigDecimal entryPrice, BigDecimal targetPrice,
                             BigDecimal multiplier, Instant startedAt) {
        UUID botId = ctx.botId();
        boolean dryRun = ctx.dryRun();
        if (repo.findAllByBotIdAndDryRunOrderByGenerationAsc(botId, dryRun).stream()
                .anyMatch(row -> row.getKind() == GenerationKind.RECOVERY
                        && episodeId.equals(row.getMarginEpisodeId()))) {
            return;
        }

        repo.save(GridGenerationEntity.builder()
                .botId(botId)
                .dryRun(dryRun)
                .generation(generation)
                .kind(GenerationKind.RECOVERY)
                .direction(direction)
                .margin(true)
                .marginEpisodeId(episodeId)
                .entryPrice(entryPrice)
                .targetPrice(targetPrice)
                .multiplier(multiplier)
                .origin("HEDGE")
                .startedAt(startedAt == null ? Instant.now() : startedAt)
                .startSeq(ledgerRepo.maxSeq(botId, dryRun))
                .build());
    }

    /** Закрывает строку эпизода: дальше её деньги уже не растут. */
    @Transactional
    public void closeRecovery(BotExecutionContext ctx, UUID episodeId, Instant endedAt) {
        for (GridGenerationEntity row : repo.findAllByBotIdAndDryRunOrderByGenerationAsc(
                ctx.botId(), ctx.dryRun())) {
            if (row.getKind() != GenerationKind.RECOVERY
                    || !episodeId.equals(row.getMarginEpisodeId())
                    || row.getEndedAt() != null) {
                continue;
            }
            row.setEndedAt(endedAt == null ? Instant.now() : endedAt);
            row.setEndSeq(ledgerRepo.maxSeq(ctx.botId(), ctx.dryRun()));
            repo.save(row);
        }
    }

    private static final class Aggregate {
        private int cycles;
        private BigDecimal cyclesPnl = BigDecimal.ZERO;
        private BigDecimal liquidationPnl = BigDecimal.ZERO;
        private String currency;
    }
}
