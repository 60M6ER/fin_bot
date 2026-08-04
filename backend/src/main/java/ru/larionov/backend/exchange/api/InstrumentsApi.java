package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentDetails;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentSnapshot;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentsQuery;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;

import java.util.List;
import java.util.Set;

/**
 * Справочник инструментов конкретной биржи.
 *
 * Требование к реализациям: {@link InstrumentId#uid()} обязан быть стабильным между
 * выгрузками — по паре (биржа, uid) строится ключ нашего справочника. У T-Invest uid
 * приходит от брокера; адаптер биржи без собственного uid (например криптобиржи)
 * обязан синтезировать его детерминированно, например "BINANCE:BTCUSDT".
 * На figi полагаться нельзя: у опционов T-Invest его нет вовсе.
 */
public interface InstrumentsApi {

    /**
     * Полная выгрузка справочника — источник для фоновой синхронизации.
     *
     * Вызов тяжёлый (десятки тысяч строк, единицы секунд), поэтому дёргать его из
     * обработчика HTTP-запроса нельзя. Реализация может не поддерживать часть типов —
     * тогда она просто их не возвращает.
     *
     * @param kinds типы для выгрузки; null или пустое множество = всё, что умеет адаптер
     */
    default List<InstrumentSnapshot> listAll(Set<InstrumentKind> kinds) {
        throw new UnsupportedOperationException("Адаптер не умеет выгружать справочник целиком");
    }

    /**
     * Живой фильтр по бирже. UI им не пользуется — он ищет по нашему справочнику;
     * это аварийный путь на случай, когда справочник ещё не синхронизирован.
     */
    List<InstrumentBrief> list(InstrumentsQuery q);

    InstrumentDetails get(InstrumentId id);

    TradingConstraints getConstraints(InstrumentId id);
}
