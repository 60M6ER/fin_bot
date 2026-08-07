package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.entity.GridGenerationEntity;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;
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
                                            Instant startedAt) {
        UUID botId = ctx.botId();
        boolean dryRun = ctx.dryRun();

        List<GridGenerationEntity> rows = repo.findAllByBotIdAndDryRunOrderByGenerationAsc(botId, dryRun);
        if (rows.stream().anyMatch(row -> row.getGeneration() == generation)) {
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
            result.add(new GridGenerationDto(
                    row.getGeneration(),
                    row.getLowerPrice(),
                    row.getUpperPrice(),
                    row.getLevels(),
                    row.getOrigin(),
                    row.getStartedAt(),
                    row.getEndedAt(),
                    row.getEndedAt() == null,
                    transitionCost,
                    window.cycles,
                    window.cyclesPnl,
                    transitionCost.add(window.cyclesPnl),
                    window.currency
            ));
            transitionCost = window.liquidationPnl;
        }
        return result;
    }

    private Aggregate aggregate(List<MoneyLedgerEntity> ledger, GridGenerationEntity generation) {
        Aggregate aggregate = new Aggregate();

        for (MoneyLedgerEntity row : ledger) {
            if (row.getSeq() == null
                    || row.getSeq() <= generation.getStartSeq()
                    || (generation.getEndSeq() != null && row.getSeq() > generation.getEndSeq())) {
                continue;
            }
            if (aggregate.currency == null && row.getCurrency() != null) {
                aggregate.currency = row.getCurrency();
            }
            if (row.getEntryType() != LedgerEntryType.CYCLE_RESULT) {
                continue;
            }

            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            if (row.getGridLevel() == null) {
                // Продажа без уровня — принудительное закрытие позиции перед
                // перестановкой: это не цикл сетки, а цена выхода из неё.
                aggregate.liquidationPnl = aggregate.liquidationPnl.add(amount);
            } else {
                aggregate.cycles++;
                aggregate.cyclesPnl = aggregate.cyclesPnl.add(amount);
            }
        }
        return aggregate;
    }

    private static final class Aggregate {
        private int cycles;
        private BigDecimal cyclesPnl = BigDecimal.ZERO;
        private BigDecimal liquidationPnl = BigDecimal.ZERO;
        private String currency;
    }
}
