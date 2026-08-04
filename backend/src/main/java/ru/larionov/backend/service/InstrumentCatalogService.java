package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.InstrumentDto;
import ru.larionov.backend.entity.InstrumentEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentBrief;
import ru.larionov.backend.exchange.api.model.instrument.InstrumentDetails;
import ru.larionov.backend.repository.InstrumentRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Чтение справочника инструментов: поиск для автокомплита и резолв uid в подпись.
 *
 * Всё идёт по нашей таблице, а не по бирже — поэтому работает быстро и без активного
 * подключения к брокеру.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstrumentCatalogService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final InstrumentRepository repo;
    private final ExchangeRuntimeService runtimeService;

    @Transactional(readOnly = true)
    public List<InstrumentDto> search(ExchangeType exchange,
                                      String query,
                                      InstrumentKind kind,
                                      MarketSegment segment,
                                      boolean onlyTradable,
                                      Integer limit) {
        if (exchange == null) {
            throw new IllegalArgumentException("Не указана биржа");
        }

        String q = normalize(query);
        String escaped = escapeLike(q);
        String prefix = q.isEmpty() ? "%" : escaped + "%";
        String contains = q.isEmpty() ? "%" : "%" + escaped + "%";
        int lim = Math.clamp(limit == null ? DEFAULT_LIMIT : limit, 1, MAX_LIMIT);

        return repo.search(
                        exchange.name(), q, prefix, contains,
                        kind == null ? null : kind.name(),
                        segment == null ? null : segment.name(),
                        onlyTradable, lim)
                .stream()
                .map(InstrumentCatalogService::toDto)
                .toList();
    }

    /**
     * Резолв uid в подпись — без него выпадающий список при открытии существующего бота
     * показал бы голый uid из конфига.
     */
    @Transactional(readOnly = true)
    public InstrumentDto getByUid(String uid, ExchangeType exchange) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("Не указан uid инструмента");
        }

        Optional<InstrumentEntity> row = exchange == null
                ? findByUidAcrossExchanges(uid)
                : repo.findByExchangeAndInstrumentUid(exchange, uid);

        if (row.isPresent()) {
            return toDto(row.get());
        }

        // Справочник может быть ещё не синхронизирован: свежая база, подключение только
        // что подняли. Спрашиваем брокера напрямую, чтобы форма всё равно показала подпись.
        if (exchange != null) {
            Optional<InstrumentDto> live = tryResolveLive(exchange, uid);
            if (live.isPresent()) {
                return live.get();
            }
        }

        throw new NotFoundException("Инструмент не найден в справочнике: " + uid);
    }

    private Optional<InstrumentEntity> findByUidAcrossExchanges(String uid) {
        List<InstrumentEntity> found = repo.findAllByInstrumentUid(uid);
        if (found.size() > 1) {
            // Пока биржа одна, это невозможно. Когда появится вторая — лучше увидеть варнинг,
            // чем молча отдать инструмент не той биржи.
            log.warn("uid {} встречается на нескольких биржах ({}) — отдаём первый; "
                    + "вызывающему стоит передавать exchange", uid, found.size());
        }
        return found.stream().findFirst();
    }

    private Optional<InstrumentDto> tryResolveLive(ExchangeType exchange, String uid) {
        return runtimeService.findRunningByExchange(exchange).flatMap(handler -> {
            try {
                InstrumentDetails details = handler.client().instruments()
                        .get(new InstrumentId(uid, null));
                return Optional.of(toDto(exchange, details));
            } catch (Exception e) {
                // Сбой брокера не должен превращать поиск подписи в 500 — пусть будет 404.
                log.debug("Живой резолв инструмента {} по {} не удался: {}", uid, exchange, e.toString());
                return Optional.empty();
            }
        });
    }

    private static String normalize(String query) {
        return query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Без экранирования ввод «%» отдал бы всю таблицу, а «_» матчил бы любой символ.
     * Обратный слэш — LIKE-эскейп по умолчанию в Postgres, поэтому его удваиваем первым.
     */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private static InstrumentDto toDto(InstrumentEntity e) {
        return new InstrumentDto(
                e.getInstrumentUid(),
                e.getFigi(),
                e.getExchange(),
                e.getKind(),
                e.getSegment(),
                e.getTicker(),
                e.getName(),
                e.getClassCode(),
                e.getVenue(),
                e.getCurrency(),
                e.getLot(),
                e.getMinPriceIncrement(),
                e.isApiTradeAvailable(),
                e.isActive(),
                e.getExpiresAt()
        );
    }

    private static InstrumentDto toDto(ExchangeType exchange, InstrumentDetails d) {
        InstrumentBrief b = d.brief();
        return new InstrumentDto(
                b.id().uid(),
                b.id().figi(),
                exchange,
                b.kind(),
                b.segment(),
                b.ticker(),
                b.name(),
                b.classCode(),
                b.venue(),
                b.quoteCurrency(),
                d.lot(),
                d.minPriceIncrement(),
                d.apiTradeAvailable(),
                true,
                null
        );
    }
}
