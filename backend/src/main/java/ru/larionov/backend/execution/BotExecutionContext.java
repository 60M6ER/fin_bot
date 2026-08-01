package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Всё, что гейтвею нужно знать о боте, чтобы работать от его имени.
 *
 * @param dryRun          бумажный режим: ордера идут в тот же журнал, но не на биржу
 * @param maxCapital      потолок задействованных денег, null = без ограничения
 * @param maxPositionLots потолок позиции в лотах, null = без ограничения
 * @param maxOrdersPerDay потолок постановок за сутки, null = без ограничения
 * @param maxOrdersPerMinute защита от разгона циклом: на стриме нет естественного
 *                           ограничителя частоты, каким был период поллинга
 */
public record BotExecutionContext(
        UUID botId,
        UUID connectionId,
        AccountId accountId,
        InstrumentId instrumentId,
        boolean dryRun,
        BigDecimal maxCapital,
        Long maxPositionLots,
        Integer maxOrdersPerDay,
        Integer maxOrdersPerMinute
) {
}
