package ru.larionov.backend.exchange.poloniex;

import ru.larionov.backend.exchange.api.TradingCalendarApi;
import ru.larionov.backend.exchange.api.model.instrument.TradingCalendar;
import ru.larionov.backend.exchange.api.model.instrument.TradingCalendarQuery;

import java.util.List;

/**
 * Расписания у криптобиржи нет: торги идут круглосуточно и без выходных.
 *
 * Пустой календарь, а не выдуманные интервалы «с 00:00 до 24:00»: расписание
 * площадки и так не отвечает на вопрос «можно ли сейчас поставить заявку» — на него
 * отвечает {@code MarketDataApi.getTradingStatus}, и именно там учитываются паузы
 * и режим POST_ONLY, которые у Poloniex случаются.
 */
public class PoloniexTradingCalendarApi implements TradingCalendarApi {

    @Override
    public TradingCalendar getCalendar(TradingCalendarQuery q) {
        return new TradingCalendar(List.of());
    }
}
