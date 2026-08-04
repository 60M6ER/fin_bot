package ru.larionov.backend.model;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;

import java.time.Instant;
import java.util.Set;

/**
 * Итог последней синхронизации справочника по бирже. Живёт в памяти.
 *
 * Отдельная таблица событий для джобы, которая бегает несколько раз в сутки, — избыточна,
 * а BotEventService не годится: он привязан к боту внешним ключом, а у синхронизации бота нет.
 *
 * @param running         синхронизация идёт прямо сейчас
 * @param syncedKinds     типы, которые удалось выгрузить целиком
 * @param failedKinds     типы, упавшие на выгрузке; для них справочник остался прежним
 */
public record InstrumentSyncStatus(
        ExchangeType exchange,
        boolean running,
        Instant startedAt,
        Instant finishedAt,
        int upserted,
        int deactivated,
        Set<InstrumentKind> syncedKinds,
        Set<InstrumentKind> failedKinds,
        String lastError
) {
    public static InstrumentSyncStatus started(ExchangeType exchange, Instant at) {
        return new InstrumentSyncStatus(exchange, true, at, null, 0, 0, Set.of(), Set.of(), null);
    }

    public boolean ok() {
        return lastError == null && failedKinds.isEmpty();
    }
}
