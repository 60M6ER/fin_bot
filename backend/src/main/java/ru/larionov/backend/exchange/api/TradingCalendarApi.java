package ru.larionov.backend.exchange.api;

import ru.larionov.backend.exchange.api.model.instrument.TradingCalendar;
import ru.larionov.backend.exchange.api.model.instrument.TradingCalendarQuery;

/**
 * Расписание торгов ПЛОЩАДОК.
 *
 * Осознанно не отвечает на вопрос «можно ли сейчас поставить заявку»: расписание
 * площадки не равно состоянию инструмента, и попытка вывести из него готовность
 * биржи принимать заявки уже приводила к тому, что бот всю ночь долбился заявками
 * в закрытую биржу. Для этого есть {@code MarketDataApi.getTradingStatus}.
 */
public interface TradingCalendarApi {
    TradingCalendar getCalendar(TradingCalendarQuery q);
}
