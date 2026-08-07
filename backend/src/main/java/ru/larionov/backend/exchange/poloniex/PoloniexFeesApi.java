package ru.larionov.backend.exchange.poloniex;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.larionov.backend.exchange.api.FeesApi;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Тарифные ставки счёта.
 *
 * Ставка зависит от оборота за 30 дней и потому меняется, но не ежеминутно —
 * кэшируем на час. Ходить за ней на каждую проверку сетки нельзя: эта проверка
 * выполняется при каждой перестройке, а лимит запросов у биржи общий с торговлей.
 *
 * Если ставку получить не удалось, отдаём заведомо КОНСЕРВАТИВНУЮ: завышенная
 * комиссия заставит бота отказаться от слишком тесной сетки, заниженная — молча
 * пустить в торговлю, которая не окупает издержки.
 */
@Slf4j
@RequiredArgsConstructor
public class PoloniexFeesApi implements FeesApi {

    /** Базовый тариф Poloniex для мелкого оборота: 0.045% maker, 0.075% taker. */
    private static final FeeInfo FALLBACK = new FeeInfo(new BigDecimal("0.00045"), new BigDecimal("0.00075"));

    private static final Duration TTL = Duration.ofHours(1);

    private final PoloniexRest rest;

    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Override
    public FeeInfo getFeeInfo(AccountId accountId, InstrumentId instrumentId) {
        Cached cached = cache.get();
        if (cached != null && cached.until().isAfter(Instant.now())) {
            return cached.fees();
        }

        try {
            var info = rest.call("тарифы", rest.api().feeInfo());
            if (info == null || info.getMakerRate() == null || info.getTakerRate() == null) {
                return FALLBACK;
            }
            FeeInfo fees = new FeeInfo(
                    new BigDecimal(info.getMakerRate()),
                    new BigDecimal(info.getTakerRate()));
            cache.set(new Cached(fees, Instant.now().plus(TTL)));
            return fees;
        } catch (Exception e) {
            log.debug("Не удалось получить тарифы Poloniex, беру консервативные: {}", e.getMessage());
            return FALLBACK;
        }
    }

    private record Cached(FeeInfo fees, Instant until) {
    }
}
