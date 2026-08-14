package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.larionov.backend.dto.BotAccountingDto;
import ru.larionov.backend.dto.BotValuationDto;
import ru.larionov.backend.dto.ConnectionValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.repository.InstrumentRepository;
import ru.larionov.backend.service.ExchangeConnectionContextResolver;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.money.CurrencyCode;
import ru.larionov.backend.money.FxRate;
import ru.larionov.backend.money.FxRateService;
import ru.larionov.backend.service.AppSettingKeys;
import ru.larionov.backend.service.AppSettingService;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.grid.GridConfig;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сколько у бота денег и сколько он заработал.
 *
 * Отвечает на один вопрос, который денежная книга сама по себе не закрывает: открытые
 * лоты стоят не столько, сколько за них заплатили, а столько, сколько за них дают
 * сейчас. Цену берём из {@link LastPriceCache} — того же потока, по которому бот
 * принимает решения.
 *
 * <h3>Почему здесь кэш</h3>
 * Фронтенд опрашивает список ботов раз в 4 секунды. Полная сводка по книге — это
 * загрузка всего журнала бота, поэтому кэшируем её и сбрасываем по
 * {@link LedgerChangedEvent}, то есть ровно тогда, когда бот действительно торговал.
 * Цена при этом читается заново на КАЖДЫЙ вызов (это хеш-лукап), так что рыночная
 * оценка живёт с частотой опроса, а дорогая часть — с частотой сделок.
 * Значения точные, а не приближённые: на экране деньги.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BotValuationService {

    private final AccountingService accounting;
    private final LastPriceCache lastPriceCache;
    private final ru.larionov.backend.runtime.ShortMarginRateCache shortMarginRates;
    private final ExchangeBalanceService exchangeBalance;
    private final ExchangeConnectionRepository connectionRepo;
    private final ExchangeConnectionContextResolver settingsResolver;
    private final InstrumentRepository instrumentRepo;
    private final ObjectMapper objectMapper;
    private final FxRateService fx;
    private final AppSettingService settings;

    private final Map<UUID, BotAccountingDto> summaryCache = new ConcurrentHashMap<>();
    private final Map<UUID, ParsedConfig> configCache = new ConcurrentHashMap<>();

    /** Для списков ботов: дешёвый путь, ремонтный проход по книге не запускается. */
    public BotValuationDto valuation(BotEntity bot) {
        ParsedConfig cfg = config(bot);
        BotAccountingDto summary = summaryCache.computeIfAbsent(
                bot.getId(), id -> accounting.summaryFast(id, cfg.dryRun()));
        return enrich(bot, summary, cfg);
    }

    /** Для карточки бота: полный путь с ремонтом книги, семантика прежняя. */
    public BotValuationDto accounting(BotEntity bot, Boolean dryRunOverride) {
        ParsedConfig cfg = config(bot);
        boolean dryRun = dryRunOverride != null ? dryRunOverride : cfg.dryRun();
        BotAccountingDto summary = accounting.summary(bot.getId(), dryRun);
        if (dryRunOverride == null || dryRunOverride == cfg.dryRun()) {
            summaryCache.put(bot.getId(), summary);
        }
        return enrich(bot, summary, cfg);
    }

    /**
     * Сводный кошелёк подключения: сумма по его ботам плюс деньги, никому не розданные.
     *
     * Боты группируются ПО ВАЛЮТАМ. Раньше валюта бралась у первого бота и всё
     * складывалось независимо от того, совпадают ли валюты остальных: подключение
     * с ботами в рублях и в USDT давало молча неверную сумму. Теперь каждая валюта
     * считается отдельно, а свести их в одно число — задача валюты показа.
     *
     * @param bots все боты подключения — передаются снаружи, чтобы список подключений
     *             не устраивал запрос в БД на каждую строку
     */
    public ConnectionValuationDto connectionValuation(UUID connectionId, List<BotEntity> bots) {
        String displayCurrency = displayCurrency();

        // Расчётная валюта — свойство ПОДКЛЮЧЕНИЯ, а не его ботов. Прежде её выбирали
        // голосованием ботов, а при их отсутствии — «той, которой больше на счёте»;
        // на спотовом кошельке с пылью из десятка монет это давало баланс в HTX.
        ExchangeBalanceService.ExchangeBalance wallet = exchangeBalance.balance(
                connectionId, configuredBaseCurrency(connectionId));
        String currency = wallet.baseCurrency();

        List<BotValuationDto> valuations = bots.stream().map(this::valuation).toList();

        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        Map<String, BigDecimal> byCurrency = new HashMap<>();
        int valued = 0;
        boolean incomplete = wallet.incomplete();

        for (BotValuationDto v : valuations) {
            String botCurrency = CurrencyCode.normalize(v.currency());
            boolean sameAsMain = CurrencyCode.sameMoney(botCurrency, currency);

            BigDecimal botPnl = v.totalPnl() != null ? v.totalPnl() : v.realizedPnl();
            if (sameAsMain) {
                pnl = pnl.add(nvl(botPnl));
            }

            if (v.equity() == null || v.workingBudget() == null) {
                // Бот без бюджета или с позицией, но без цены: честно отмечаем неполноту
                // вместо того, чтобы подставить ноль и показать заниженную сумму.
                incomplete = true;
                continue;
            }

            if (botCurrency != null) {
                byCurrency.merge(botCurrency, v.equity(), BigDecimal::add);
            } else {
                // Валюта бота неизвестна — в разбивку он не попадёт, и об этом надо сказать.
                incomplete = true;
            }

            // Числовые поля подключения остаются в его расчётной валюте: смешивать
            // их с чужими нельзя, а пересчитывать здесь — значит протащить курс
            // в места, которые про него знать не должны.
            if (!sameAsMain) {
                continue;
            }
            allocated = allocated.add(v.workingBudget());
            balance = balance.add(v.equity());
            valued++;
        }

        /*
         * Что осталось незанятым = сколько стоит счёт МИНУС то, что боты уже считают
         * своим (их деньги плюс их позиции по рынку).
         *
         * Раньше вычиталось иначе: из СВОБОДНЫХ денег счёта вычитались свободные
         * деньги ботов. Обе части считались по-разному — счёт не показывал денег
         * под выставленными заявками, а бот считал их своими свободными, — и разница
         * дважды теряла одни и те же деньги. 08.08.2026 на Poloniex это дало
         * «свободно −48.81 USDT» при 118 на счёте и 125 розданных: настоящий минус
         * был около семи.
         *
         * Минус сам по себе — не ошибка расчёта, а розданные бюджеты сверх того,
         * что есть. Показать его важнее, чем спрятать.
         */
        BigDecimal total = wallet.total();
        BigDecimal unallocated = total == null ? null : total.subtract(balance);

        // Свободные деньги подключения — та часть счёта, которая всё ещё деньги.
        BigDecimal freeCash = wallet.cash();

        // Остатки, не покрытые ботами, показываем в валюте счёта: это то же самое
        // ничейное, что и unallocated, только уже пересчитанное в расчётную валюту.
        if (unallocated != null && currency != null) {
            byCurrency.merge(CurrencyCode.normalize(currency), unallocated, BigDecimal::add);
        }

        return withDisplayTotal(new ConnectionValuationDto(
                bots.size(), valued, allocated, balance, pnl,
                freeCash, unallocated, total, incomplete, currency,
                byCurrency, null, displayCurrency, null, null));
    }

    /** Расчётная валюта из настроек подключения; null — не задана, спросим биржу. */
    private String configuredBaseCurrency(UUID connectionId) {
        try {
            return connectionRepo.findById(connectionId)
                    .map(settingsResolver::parseSettings)
                    .map(ExchangeConnectionSettings::baseCurrency)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("Не удалось прочитать настройки подключения {}: {}", connectionId, e.getMessage());
            return null;
        }
    }


    /**
     * Досчитывает сводный итог в валюте показа.
     *
     * Единственное место, где курс вообще участвует в расчёте. Если он неизвестен —
     * итог остаётся null и помечается неполнотой: показать разбивку без свода честнее,
     * чем свести по выдуманному курсу.
     */
    private ConnectionValuationDto withDisplayTotal(ConnectionValuationDto dto) {
        if (dto.byCurrency().isEmpty()) {
            return dto;
        }

        BigDecimal sum = BigDecimal.ZERO;
        String fxSource = null;
        Instant fxAsOf = null;
        boolean incomplete = dto.incomplete();

        for (Map.Entry<String, BigDecimal> entry : dto.byCurrency().entrySet()) {
            Optional<FxRate> rate = fx.rate(entry.getKey(), dto.displayCurrency());
            if (rate.isEmpty()) {
                incomplete = true;
                return new ConnectionValuationDto(
                        dto.botCount(), dto.valuedBotCount(), dto.allocatedBudget(),
                        dto.botsBalance(), dto.botsPnl(), dto.freeCash(), dto.unallocatedCash(),
                        dto.total(), incomplete, dto.currency(),
                        dto.byCurrency(), null, dto.displayCurrency(), null, null);
            }
            sum = sum.add(entry.getValue().multiply(rate.get().rate()));
            // Подписываем итог самым «слабым» звеном: если хоть одна конвертация
            // реальная, показываем именно её источник и её дату, а не IDENTITY.
            if (!"IDENTITY".equals(rate.get().source())) {
                fxSource = rate.get().source();
                fxAsOf = rate.get().asOf();
            }
        }

        return new ConnectionValuationDto(
                dto.botCount(), dto.valuedBotCount(), dto.allocatedBudget(),
                dto.botsBalance(), dto.botsPnl(), dto.freeCash(), dto.unallocatedCash(),
                dto.total(), incomplete, dto.currency(),
                dto.byCurrency(), sum.setScale(2, java.math.RoundingMode.HALF_UP),
                dto.displayCurrency(), fxSource, fxAsOf);
    }

    /** Валюта показа из настроек; по умолчанию рубли. */
    public String displayCurrency() {
        return CurrencyCode.normalize(
                settings.get(AppSettingKeys.DISPLAY_CURRENCY, CurrencyCode.RUB));
    }

    /** Бот удалён — чистим обе карты, чтобы они не текли. */
    public void forget(UUID botId) {
        summaryCache.remove(botId);
        configCache.remove(botId);
        lastPriceCache.evict(botId);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLedgerChanged(LedgerChangedEvent event) {
        summaryCache.remove(event.botId());
    }

    // ==============================
    // РАСЧЁТ
    // ==============================

    /**
     * Валюта для показа.
     *
     * У бота без единой сделки денежная книга пуста, и валюты в ней ещё нет — а бюджет
     * ему уже задан, и показывать его без валюты некрасиво. Тогда берём валюту
     * инструмента из локального справочника.
     *
     * Именно из справочника, а НЕ у брокера: этот метод лежит на пути списка ботов
     * (опрос раз в 4 секунды) и на пути уведомлений (внутри транзакции записи события,
     * на критическом пути запуска бота). Сетевой вызов ради подписи к числу — плохой
     * размен в любой из этих трёх точек.
     */
    private String displayCurrency(BotAccountingDto s, ParsedConfig cfg) {
        if (s.currency() != null && !s.currency().isBlank()) {
            return s.currency();
        }
        return cfg.instrumentCurrency();
    }

    private BotValuationDto enrich(BotEntity bot, BotAccountingDto s, ParsedConfig cfg) {
        Optional<LastPriceCache.CachedPrice> cached = lastPriceCache.get(bot.getId());
        BigDecimal lastPrice = cached.map(LastPriceCache.CachedPrice::price).orElse(null);
        Instant lastPriceAt = cached.map(LastPriceCache.CachedPrice::receivedAt).orElse(null);

        BigDecimal marketValue = null;
        BigDecimal unrealizedPnl = null;
        BigDecimal totalPnl = null;
        if (nvl(s.openQuantity()).signum() == 0) {
            // Нетто-ноль: цена ни на что не влияет, потому что умножается на ноль.
            // Требовать её здесь означало бы прятать баланс бота, который просто
            // ещё не купил.
            //
            // Оговорка про знаковую книгу: нетто-ноль бывает не только у пустого
            // бота, но и у захеджированного — с равными длинной и короткой ногами.
            // Числа от этого не меняются (общая ветка при нулевом количестве даёт
            // ровно то же самое), но «позиции нет» про такого бота сказать нельзя:
            // обе его ноги живые и обе стоят обеспечения. Кому нужна именно
            // открытая экспозиция, тот спрашивает Inventory.grossExposure().
            marketValue = BigDecimal.ZERO;
            unrealizedPnl = BigDecimal.ZERO.subtract(nvl(s.costBasisOpen()));
            totalPnl = nvl(s.realizedPnl()).add(unrealizedPnl);
        } else if (lastPrice != null) {
            // Количество уже в единицах базового актива, цена — за такую единицу,
            // поэтому оценка не зависит от лотности и сходится с averageEntryPrice.
            marketValue = lastPrice.multiply(s.openQuantity());
            unrealizedPnl = marketValue.subtract(nvl(s.costBasisOpen()));
            totalPnl = nvl(s.realizedPnl()).add(unrealizedPnl);
        }

        /*
         * Баланс бота = деньги + позиция:
         *
         *   бюджет + реализованный P/L − выведенная прибыль − себестоимость + рыночная стоимость
         *
         * Первые три слагаемых — свободные деньги бота, последние два — во что они
         * превратились. Свернув, получаем то же самое короче: рабочий бюджет плюс
         * нереализованный результат (при WITHDRAW выведенная прибыль ровно гасит
         * реализованную, при COMPOUND она нулевая и остаётся в обороте).
         */
        BigDecimal workingBudget = cfg.workingBudget(s.realizedPnl());
        BigDecimal equity = (workingBudget == null || unrealizedPnl == null)
                ? null
                : workingBudget.add(unrealizedPnl);

        /*
         * Обеспечение считается ОТДЕЛЬНО и в equity не входит.
         *
         * Заёмные деньги боту не принадлежат: выручка от короткой продажи уже погашена
         * отрицательной рыночной стоимостью позиции, поэтому баланс остаётся «свои
         * деньги плюс результат». А вот вопрос «сколько бот занял у брокера» этим
         * балансом не отвечается вовсе — на него и отвечает эта величина.
         */
        BigDecimal shortQuantity = nvl(s.shortQuantity());
        BigDecimal usedMargin = shortQuantity.signum() == 0
                ? BigDecimal.ZERO
                : (lastPrice == null
                        ? null
                        : shortMarginRates
                                .requiredMargin(instrumentKey(bot), shortQuantity.multiply(lastPrice))
                                .orElse(null));

        return new BotValuationDto(
                s.dryRun(), s.cashFlow(), s.costBasisOpen(), s.realizedPnl(), s.paidCommission(),
                s.openQuantity(), s.averageEntryPrice(), displayCurrency(s, cfg),
                lastPrice, lastPriceAt, marketValue, unrealizedPnl, totalPnl,
                cfg.budget(), workingBudget, cfg.withdrawnProfit(s.realizedPnl()), equity,
                cfg.profitPolicy(), cfg.sizingMode(),
                shortQuantity, usedMargin);
    }

    /** Ключ инструмента бота: тот же, под которым ставку положила фабрика. */
    private String instrumentKey(ru.larionov.backend.entity.BotEntity bot) {
        return config(bot).instrumentUid();
    }

    // ==============================
    // КОНФИГУРАЦИЯ
    // ==============================

    /**
     * Разбор strategy_config кэшируем по updatedAt бота: он меняется только при
     * сохранении настроек. Заодно избавляет список от resolveDryRun, который ради
     * одного флага поднимал две сотни последних ордеров.
     */
    private ParsedConfig config(BotEntity bot) {
        ParsedConfig cached = configCache.get(bot.getId());
        if (cached != null && cached.matches(bot.getUpdatedAt())) {
            return cached;
        }
        ParsedConfig parsed = parse(bot);
        configCache.put(bot.getId(), parsed);
        return parsed;
    }

    private ParsedConfig parse(BotEntity bot) {
        String json = bot.getStrategyConfig() == null || bot.getStrategyConfig().isBlank()
                ? "{}" : bot.getStrategyConfig();

        boolean dryRun = false;
        try {
            dryRun = objectMapper.readValue(json, BotRuntimeConfig.class).dryRun();
        } catch (Exception e) {
            log.debug("Бот {}: не удалось прочитать dryRun из конфигурации: {}", bot.getId(), e.getMessage());
        }

        GridConfig grid = null;
        try {
            grid = objectMapper.readValue(json, GridConfig.class);
        } catch (Exception e) {
            // Невалидная или неполная конфигурация — не повод не показать реализованный
            // P/L. Такой бот всё равно не запустится, и скажет об этом при запуске.
            log.debug("Бот {}: конфигурация стратегии не разобрана: {}", bot.getId(), e.getMessage());
        }

        String instrumentUid = null;
        try {
            instrumentUid = objectMapper.readValue(json, BotRuntimeConfig.class).instrumentUid();
        } catch (Exception e) {
            log.debug("Бот {}: инструмент из конфигурации не прочитан: {}", bot.getId(), e.getMessage());
        }

        return new ParsedConfig(bot.getUpdatedAt(), dryRun, grid,
                instrumentCurrency(json), instrumentUid);
    }

    /**
     * Валюта инструмента из локального справочника. Разбирается один раз вместе
     * с конфигурацией и живёт в том же кэше — на горячий путь запрос не попадает.
     */
    private String instrumentCurrency(String json) {
        try {
            String uid = objectMapper.readValue(json, BotRuntimeConfig.class).instrumentUid();
            if (uid == null || uid.isBlank()) {
                return null;
            }
            return instrumentRepo.findAllByInstrumentUid(uid).stream()
                    .map(i -> i.getCurrency())
                    .filter(c -> c != null && !c.isBlank())
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private record ParsedConfig(Instant updatedAt, boolean dryRun, GridConfig grid,
                                String instrumentCurrency, String instrumentUid) {

        boolean matches(Instant botUpdatedAt) {
            return Objects.equals(updatedAt, botUpdatedAt);
        }

        BigDecimal budget() {
            return grid == null ? null : grid.budget();
        }

        BigDecimal workingBudget(BigDecimal realizedPnl) {
            return grid == null ? null : grid.workingBudget(() -> realizedPnl);
        }

        BigDecimal withdrawnProfit(BigDecimal realizedPnl) {
            return grid == null ? BigDecimal.ZERO : grid.withdrawnProfit(realizedPnl);
        }

        String profitPolicy() {
            return grid == null || grid.profitPolicy() == null ? null : grid.profitPolicy().name();
        }

        String sizingMode() {
            return grid == null || grid.sizingMode() == null ? null : grid.sizingMode().name();
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
