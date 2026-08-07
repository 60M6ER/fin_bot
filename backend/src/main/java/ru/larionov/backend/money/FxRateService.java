package ru.larionov.backend.money;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.FxRateEntity;
import ru.larionov.backend.repository.FxRateRepository;
import ru.larionov.backend.service.AppSettingKeys;
import ru.larionov.backend.service.AppSettingService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Курсы валют — ТОЛЬКО для показа.
 *
 * <h3>Главное ограничение</h3>
 * Ни размер заявки, ни бюджет бота, ни один риск-лимит не имеют права читать
 * сконвертированное число. Бюджет бота всегда выражен в валюте котировки его
 * инструмента, и таким остаётся. Иначе скачок курса молча передвинул бы объём
 * заявки или сработал бы стоп-лимит без единой сделки.
 *
 * Ограничение закреплено структурно: пакет {@code money} не импортируется ни из
 * {@code strategy}, ни из {@code execution}, и это проверяется тестом.
 *
 * <h3>Как достаётся курс</h3>
 * Свежий из выбранного источника → при неудаче из любого другого → при неудаче
 * последний сохранённый в БД. Если и там пусто — {@link Optional#empty()}, и
 * вызывающий обязан показать неполноту, а не подставить ноль.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FxRateService {

    /**
     * Курс не меняется ежесекундно, а ЦБ обновляет его раз в сутки. Пять минут —
     * компромисс: биржевой источник остаётся живым, а список подключений (опрос раз
     * в несколько секунд) не превращается в поток запросов наружу.
     */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final List<FxRateProvider> providers;
    private final FxRateRepository repo;
    private final AppSettingService settings;

    private final AtomicReference<Cached> usdRubCache = new AtomicReference<>();

    /**
     * Курс пересчёта {@code from} → {@code to}.
     *
     * Пег учитывается: USDT к USD — это единица, и наружу мы за ней не ходим.
     */
    public Optional<FxRate> rate(String from, String to) {
        String source = CurrencyCode.pegBase(from);
        String target = CurrencyCode.pegBase(to);
        if (source == null || target == null) {
            return Optional.empty();
        }
        if (source.equals(target)) {
            return Optional.of(FxRate.identity(CurrencyCode.normalize(to)));
        }

        if (CurrencyCode.USD.equals(source) && CurrencyCode.RUB.equals(target)) {
            return usdRub();
        }
        if (CurrencyCode.RUB.equals(source) && CurrencyCode.USD.equals(target)) {
            return usdRub().map(FxRate::inverted);
        }

        // Кросс-курсы (например EUR к RUB) осознанно не поддержаны: инструментов
        // в таких валютах у нас нет, а выдумывать курс ради красоты — плохая идея.
        log.debug("Курс {}→{} не поддерживается", source, target);
        return Optional.empty();
    }

    /** Сумма, приведённая к валюте показа. Пустой Optional — курс неизвестен. */
    public Optional<BigDecimal> convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return Optional.empty();
        }
        return rate(from, to)
                .map(fx -> amount.multiply(fx.rate()).setScale(2, RoundingMode.HALF_UP));
    }

    public Optional<FxRate> usdRub() {
        Cached cached = usdRubCache.get();
        Instant now = Instant.now();
        if (cached != null && cached.until().isAfter(now)) {
            return Optional.of(cached.rate());
        }

        Optional<FxRate> fresh = fetchFresh();
        if (fresh.isPresent()) {
            usdRubCache.set(new Cached(fresh.get(), now.plus(TTL)));
            remember(fresh.get());
            return fresh;
        }

        // Свежего нет — отдаём последний известный. Отрицательный результат тоже
        // кэшируем: без этого каждый опрос списка ломился бы в недоступную сеть.
        Optional<FxRate> stored = lastKnown(CurrencyCode.USD, CurrencyCode.RUB);
        stored.ifPresent(rate -> usdRubCache.set(new Cached(rate, now.plus(TTL))));
        return stored;
    }

    /** Какой источник выбран в настройках. */
    public String preferredSource() {
        return settings.get(AppSettingKeys.FX_SOURCE, CbrFxProvider.ID);
    }

    private Optional<FxRate> fetchFresh() {
        String preferred = preferredSource();

        Optional<FxRate> fromPreferred = providers.stream()
                .filter(p -> p.id().equals(preferred))
                .findFirst()
                .flatMap(FxRateProvider::usdRub);
        if (fromPreferred.isPresent()) {
            return fromPreferred;
        }

        // Запасной источник лучше прочерка: подпись под числом всё равно назовёт,
        // откуда взялся курс, поэтому подмена не будет незаметной.
        for (FxRateProvider provider : providers) {
            if (provider.id().equals(preferred)) {
                continue;
            }
            Optional<FxRate> rate = provider.usdRub();
            if (rate.isPresent()) {
                log.debug("Курс взят у запасного источника {}: основной {} не ответил",
                        provider.id(), preferred);
                return rate;
            }
        }
        return Optional.empty();
    }

    /**
     * Транзакция здесь не объявлена намеренно: метод вызывается изнутри этого же бина,
     * то есть мимо прокси, и {@code @Transactional} на нём был бы декорацией, которая
     * не работает. Собственной транзакции {@code save} для одной строки достаточно.
     */
    private void remember(FxRate rate) {
        try {
            repo.save(FxRateEntity.builder()
                    .pair(FxRateEntity.pairOf(rate.base(), rate.quote()))
                    .rate(rate.rate())
                    .source(rate.source())
                    .asOf(rate.asOf())
                    .build());
        } catch (Exception e) {
            // Не смогли сохранить — не повод не показать курс, который уже получен.
            log.debug("Не удалось сохранить курс {}: {}", rate.base() + "/" + rate.quote(), e.getMessage());
        }
    }

    private Optional<FxRate> lastKnown(String base, String quote) {
        try {
            return repo.findById(FxRateEntity.pairOf(base, quote))
                    .map(e -> new FxRate(base, quote, e.getRate(), e.getSource(), e.getAsOf()));
        } catch (Exception e) {
            log.debug("Не удалось прочитать сохранённый курс: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private record Cached(FxRate rate, Instant until) {
    }
}
