package ru.larionov.backend.exchange.tinvest;

import com.google.protobuf.Timestamp;
import ru.larionov.backend.exchange.api.TradingCalendarApi;
import ru.larionov.backend.exchange.api.enums.TradingIntervalType;
import ru.larionov.backend.exchange.api.model.instrument.*;
import ru.larionov.backend.exchange.api.model.instrument.TradingInterval;
import ru.tinkoff.piapi.contract.v1.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * T-Invest implementation of TradingCalendarApi.
 */
public class TInvestTradingCalendarApi implements TradingCalendarApi {

    private final TInvestExchangeClient client;

    public TInvestTradingCalendarApi(TInvestExchangeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public TradingCalendar getCalendar(TradingCalendarQuery q) {
        Objects.requireNonNull(q, "q");

        TradingSchedulesRequest.Builder builder = TradingSchedulesRequest.newBuilder();

        if (q.venue() != null && !q.venue().isBlank()) {
            builder.setExchange(q.venue());
        }
        if (q.fromUtc() != null) {
            builder.setFrom(toTimestamp(q.fromUtc()));
        }
        if (q.toUtc() != null) {
            builder.setTo(toTimestamp(q.toUtc()));
        }

        TradingSchedulesResponse response = client.instrumentsStub()
                .callSyncMethod(
                        InstrumentsServiceGrpc.getTradingSchedulesMethod(),
                        stub -> stub.tradingSchedules(builder.build())
                );

        List<TradingVenueSchedule> venues = new ArrayList<>();

        for (TradingSchedule schedule : response.getExchangesList()) {
            List<TradingDaySchedule> days = new ArrayList<>();

            for (TradingDay day : schedule.getDaysList()) {
                List<ru.larionov.backend.exchange.api.model.instrument.TradingInterval> intervals = new ArrayList<>();

                for (ru.tinkoff.piapi.contract.v1.TradingInterval interval : day.getIntervalsList()) {
                    intervals.add(new ru.larionov.backend.exchange.api.model.instrument.TradingInterval(
                            toInstant(interval.getInterval().getStartTs()),
                            toInstant(interval.getInterval().getEndTs()),
                            mapIntervalType(interval)
                    ));
                }

                days.add(new TradingDaySchedule(
                        toLocalDate(day.getDate()),
                        day.getIsTradingDay(),
                        intervals
                ));
            }

            venues.add(new TradingVenueSchedule(
                    schedule.getExchange(),
                    days
            ));
        }

        return new TradingCalendar(venues);
    }

    @Override
    public TradingState getState(Instant now) {
        if (now == null) {
            now = Instant.now();
        }

        TradingCalendar calendar = getCalendar(
                new TradingCalendarQuery(null, now.minusSeconds(3600), now.plusSeconds(3600))
        );

        boolean tradable = false;
        Instant nextClose = null;
        Instant nextOpen = null;

        for (TradingVenueSchedule venue : calendar.venues()) {
            for (TradingDaySchedule day : venue.days()) {
                if (day.date() == null) continue;

                for (TradingInterval interval : day.intervals()) {
                    Instant start = interval.startUtc();
                    Instant end = interval.endUtc();

                    if (start == null || end == null) continue;

                    if (!now.isBefore(start) && now.isBefore(end)) {
                        tradable = true;
                        nextClose = end;
                    }

                    if (now.isBefore(start)) {
                        if (nextOpen == null || start.isBefore(nextOpen)) {
                            nextOpen = start;
                        }
                    }
                }
            }
        }

        return new TradingState(tradable, nextClose, nextOpen);
    }

    private static LocalDate toLocalDate(Timestamp ts) {
        Instant i = toInstant(ts);
        if (i == null) {
            return null;
        }
        return i.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private static TradingIntervalType mapIntervalType(ru.tinkoff.piapi.contract.v1.TradingInterval interval) {
        if (interval == null) {
            return null;
        }

        // In different SDK versions, type can be enum or string-like.
        // We map by name in a tolerant way.
        try {
            String name;
            if (interval.getType() == null) {
                return null;
            }
            // getType() might be an enum or a string; toString() is safe.
            name = interval.getType().toString();
            if (name == null) {
                return null;
            }
            name = name.trim();
            if (name.isEmpty()) {
                return null;
            }
            return TradingIntervalType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        if (ts == null) return null;
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
