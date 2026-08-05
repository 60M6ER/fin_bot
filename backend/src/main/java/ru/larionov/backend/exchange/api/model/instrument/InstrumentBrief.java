package ru.larionov.backend.exchange.api.model.instrument;

import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

/**
 * Идентичность и классификация инструмента — то, что показывается в списке выбора.
 *
 * @param venue нормализованная площадка (MOEX / SPB / FORTS / OTC / DEALER). Именно
 *              нормализованная: брокер отдаёт свободные строки вида MOEX_EVENING_WEEKEND,
 *              которые в выпадающем списке нечитаемы. Сырое значение живёт в
 *              {@link InstrumentSnapshot#venueRaw()}.
 */
public record InstrumentBrief(
        InstrumentId id,
        InstrumentKind kind,
        MarketSegment segment,
        String ticker,
        String name,
        String classCode,
        String venue,
        String quoteCurrency
) {
}
