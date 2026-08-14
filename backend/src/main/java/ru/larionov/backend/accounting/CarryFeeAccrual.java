package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.exchange.api.model.CarryFeeSchedule;
import ru.larionov.backend.repository.BotOrderRepository;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.runtime.LastPriceCache;
import ru.larionov.backend.service.CarryFeeResolver;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Суточное списание платы за перенос непокрытой позиции.
 *
 * Брокер берёт её за сам факт удержания короткой позиции через ночь, вне зависимости
 * от того, торгует бот или стоит. Поэтому проход идёт по ВСЕМ ботам с отрицательной
 * позицией, включая остановленных: остановка бота не отменяет его обязательств.
 *
 * <h3>Почему по журналу, а не по книге</h3>
 * Позиция берётся из журнала заявок: он знает знак с самого начала, тогда как партии
 * денежной книги пока умеют только длинную сторону. Когда книга станет знаковой,
 * оба источника обязаны сойтись — за этим уже следит проверка в риск-контроле.
 *
 * <h3>Пока шортов нет, проход ничего не делает</h3>
 * И это правильное поведение, а не заглушка: короткая позиция запрещена структурно,
 * значит и переносить нечего. Проверяется он на синтетической позиции в тесте.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CarryFeeAccrual {

    private final BotRepository botRepo;
    private final BotOrderRepository orderRepo;
    private final AccountingService accounting;
    private final LastPriceCache lastPriceCache;
    private final CarryFeeResolver carryFeeResolver;

    /**
     * Раз в час, а не раз в сутки.
     *
     * Списание всё равно идёт не чаще раза в двадцать часов — за этим следит сама
     * запись. Частый проход нужен для другого: суточный сработал бы ровно один раз,
     * и приложение, перезапущенное в этот момент, пропустило бы день молча.
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void accrue() {
        for (BotEntity bot : botRepo.findAll()) {
            try {
                accrueFor(bot);
            } catch (Exception e) {
                // Один бот не должен ронять проход по остальным: плата за перенос —
                // это учёт, а не торговля, и падать из-за неё нечему.
                log.warn("Не удалось начислить перенос боту {}: {}", bot.getId(), e.getMessage());
            }
        }
    }

    private void accrueFor(BotEntity bot) {
        boolean dryRun = dryRunOf(bot);
        BigDecimal position = orderRepo.sumPositionQuantity(bot.getId(), dryRun);
        if (position == null || position.signum() >= 0) {
            // Длинная или пустая позиция ничего не стоит в удержании: она покрыта.
            return;
        }

        BigDecimal price = lastPriceCache.get(bot.getId())
                .map(LastPriceCache.CachedPrice::price)
                .orElse(null);
        if (price == null || price.signum() <= 0) {
            // Без цены размер обязательства неизвестен. Придумывать его нельзя:
            // выдуманный перенос потом не отличить от настоящего.
            log.debug("Перенос боту {} не начислен: нет цены", bot.getId());
            return;
        }

        BigDecimal notional = position.abs().multiply(price);
        CarryFeeSchedule schedule = carryFeeResolver.schedule(bot.getExchangeConnectionId());
        BigDecimal amount = schedule.dailyCost(notional);
        if (amount.signum() <= 0) {
            return;
        }

        boolean recorded = accounting.recordCarryFee(bot.getId(), dryRun, amount, notional, null,
                "Перенос непокрытой позиции %s по цене %s: %s за сутки по ставке %s"
                        .formatted(position.abs().stripTrailingZeros().toPlainString(),
                                price.stripTrailingZeros().toPlainString(),
                                amount.stripTrailingZeros().toPlainString(),
                                schedule.dailyRate(notional).stripTrailingZeros().toPlainString()));
        if (recorded) {
            log.info("Боту {} начислен перенос {} за позицию {}", bot.getId(), amount, position);
        }
    }

    /**
     * Бумажный бот или боевой — по его последним заявкам.
     *
     * Тем же способом, что и остальной код: признак живёт в самих заявках, а не
     * отдельным полем бота, и заводить здесь второй способ его узнать значило бы
     * получить два ответа на один вопрос.
     */
    private boolean dryRunOf(BotEntity bot) {
        return orderRepo.findTop200ByBotIdOrderByCreatedAtDesc(bot.getId()).stream()
                .findFirst()
                .map(ru.larionov.backend.entity.BotOrderEntity::isDryRun)
                .orElse(false);
    }

}
