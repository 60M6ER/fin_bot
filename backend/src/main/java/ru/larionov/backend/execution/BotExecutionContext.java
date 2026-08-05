package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Всё, что гейтвею нужно знать о боте, чтобы работать от его имени.
 *
 * @param dryRun          бумажный режим: ордера идут в тот же журнал, но не на биржу
 * @param lotSize         сколько бумаг в одном лоте.
 *                        Критично: заявки и журнал считаются в ЛОТАХ, а биржа
 *                        отдаёт позицию в ШТУКАХ. Без этого множителя бот принимал
 *                        позицию в 1 лот за 10 и выставлял лишние продажи.
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
        int lotSize,
        BigDecimal maxCapital,
        Long maxPositionLots,
        Integer maxOrdersPerDay,
        Integer maxOrdersPerMinute
) {

    public BotExecutionContext {
        if (lotSize <= 0) {
            lotSize = 1;
        }
    }

    /** Позиция с биржи приходит в штуках — приводим к лотам, в которых живёт весь остальной код. */
    public BigDecimal sharesToLots(BigDecimal shares) {
        if (shares == null) {
            return null;
        }
        return shares.divide(BigDecimal.valueOf(lotSize), 0, java.math.RoundingMode.DOWN);
    }
}
