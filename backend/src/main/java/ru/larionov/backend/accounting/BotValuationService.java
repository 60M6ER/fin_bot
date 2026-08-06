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
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.AccountCashService;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.grid.GridConfig;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
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
    private final AccountCashService accountCash;
    private final ObjectMapper objectMapper;

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
     * @param bots все боты подключения — передаются снаружи, чтобы список подключений
     *             не устраивал запрос в БД на каждую строку
     */
    public ConnectionValuationDto connectionValuation(UUID connectionId, List<BotEntity> bots) {
        if (bots.isEmpty()) {
            return new ConnectionValuationDto(0, 0, null, null, null,
                    cashOnly(connectionId), cashOnly(connectionId), cashOnly(connectionId),
                    false, accountCash.dominantCurrency(connectionId));
        }

        List<BotValuationDto> valuations = bots.stream().map(this::valuation).toList();

        // Валюту подсказывает денежная книга ботов. У бота без единой сделки её ещё нет,
        // поэтому запасной вариант — валюта, которой на счёте больше всего.
        String currency = valuations.stream()
                .map(BotValuationDto::currency)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElseGet(() -> accountCash.dominantCurrency(connectionId));

        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal pnl = BigDecimal.ZERO;
        BigDecimal botsFreeCash = BigDecimal.ZERO;
        int valued = 0;
        boolean incomplete = false;

        for (BotValuationDto v : valuations) {
            BigDecimal botPnl = v.totalPnl() != null ? v.totalPnl() : v.realizedPnl();
            pnl = pnl.add(nvl(botPnl));

            if (v.equity() == null || v.workingBudget() == null) {
                // Бот без бюджета или с позицией, но без цены: честно отмечаем неполноту
                // вместо того, чтобы подставить ноль и показать заниженную сумму.
                incomplete = true;
                continue;
            }
            allocated = allocated.add(v.workingBudget());
            balance = balance.add(v.equity());
            botsFreeCash = botsFreeCash.add(v.workingBudget().subtract(nvl(v.costBasisOpen())));
            valued++;
        }

        BigDecimal freeCash = accountCash.available(connectionId, currency);
        // Отрицательный остаток — не ошибка, а перераспределённый бюджет: показываем как есть.
        BigDecimal unallocated = freeCash == null ? null : freeCash.subtract(botsFreeCash);
        BigDecimal total = unallocated == null ? null : balance.add(unallocated);

        return new ConnectionValuationDto(
                bots.size(), valued, allocated, balance, pnl,
                freeCash, unallocated, total, incomplete, currency);
    }

    private BigDecimal cashOnly(UUID connectionId) {
        return accountCash.available(connectionId, accountCash.dominantCurrency(connectionId));
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
     * ему уже задан и показывать его без валюты некрасиво. Тогда берём валюту счёта.
     */
    private String displayCurrency(BotEntity bot, BotAccountingDto s) {
        if (s.currency() != null && !s.currency().isBlank()) {
            return s.currency();
        }
        return accountCash.dominantCurrency(bot.getExchangeConnectionId());
    }

    private BotValuationDto enrich(BotEntity bot, BotAccountingDto s, ParsedConfig cfg) {
        Optional<LastPriceCache.CachedPrice> cached = lastPriceCache.get(bot.getId());
        BigDecimal lastPrice = cached.map(LastPriceCache.CachedPrice::price).orElse(null);
        Instant lastPriceAt = cached.map(LastPriceCache.CachedPrice::receivedAt).orElse(null);

        BigDecimal marketValue = null;
        BigDecimal unrealizedPnl = null;
        BigDecimal totalPnl = null;
        if (s.openShares() == 0) {
            // Позиции нет — цена не нужна и не может ни на что повлиять. Требовать её
            // здесь означало бы прятать баланс бота, который просто ещё не купил.
            marketValue = BigDecimal.ZERO;
            unrealizedPnl = BigDecimal.ZERO.subtract(nvl(s.costBasisOpen()));
            totalPnl = nvl(s.realizedPnl()).add(unrealizedPnl);
        } else if (lastPrice != null) {
            // openShares, а не openLots: так формула не зависит от лотности, которая
            // достоверно известна только пока бот запущен. Заодно оценка сходится
            // с averageEntryPrice — та тоже за штуку.
            marketValue = lastPrice.multiply(BigDecimal.valueOf(s.openShares()));
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

        return new BotValuationDto(
                s.dryRun(), s.cashFlow(), s.costBasisOpen(), s.realizedPnl(), s.paidCommission(),
                s.openLots(), s.averageEntryPrice(), displayCurrency(bot, s), s.openShares(),
                lastPrice, lastPriceAt, marketValue, unrealizedPnl, totalPnl,
                cfg.budget(), workingBudget, cfg.withdrawnProfit(s.realizedPnl()), equity,
                cfg.profitPolicy(), cfg.sizingMode());
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

        return new ParsedConfig(bot.getUpdatedAt(), dryRun, grid);
    }

    private record ParsedConfig(Instant updatedAt, boolean dryRun, GridConfig grid) {

        boolean matches(Instant botUpdatedAt) {
            return Objects.equals(updatedAt, botUpdatedAt);
        }

        BigDecimal budget() {
            return grid == null ? null : grid.budget();
        }

        BigDecimal workingBudget(BigDecimal realizedPnl) {
            return grid == null ? null : grid.workingBudget(realizedPnl);
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
