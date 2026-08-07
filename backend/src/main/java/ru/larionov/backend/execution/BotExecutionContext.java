package ru.larionov.backend.execution;

import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Всё, что гейтвею нужно знать о боте, чтобы работать от его имени.
 *
 * <h3>Об единицах</h3>
 * Домен считает в ЕДИНИЦАХ БАЗОВОГО АКТИВА: штуках бумаг, монетах. Это единственная
 * единица в журнале, книге, риск-контроле и стратегии. Лотность биржи — деталь
 * исполнения и живёт только здесь:
 * <ul>
 *   <li>{@code exchangeLotSize} — сколько единиц в одной ЗАЯВОЧНОЙ единице биржи.
 *       T-Invest принимает заявку целыми лотами по 1/10/100 бумаг, Poloniex — самой
 *       монетой, то есть 1;</li>
 *   <li>{@code quantityStep} — минимальный шаг количества. У T-Invest совпадает
 *       с лотом (меньше лота не купить), у криптобиржи это 10^−quantityScale;</li>
 *   <li>{@code minNotional} — минимальная сумма заявки, null если биржа её не задаёт.</li>
 * </ul>
 *
 * @param dryRun             бумажный режим: ордера идут в тот же журнал, но не на биржу
 * @param maxCapital         потолок задействованных денег, null = без ограничения
 * @param maxPositionQuantity потолок позиции, null = без ограничения
 * @param maxOrdersPerDay    потолок постановок за сутки, null = без ограничения
 * @param maxOrdersPerMinute защита от разгона циклом: на стриме нет естественного
 *                           ограничителя частоты, каким был период поллинга
 */
public record BotExecutionContext(
        UUID botId,
        UUID connectionId,
        AccountId accountId,
        InstrumentId instrumentId,
        boolean dryRun,
        BigDecimal exchangeLotSize,
        BigDecimal quantityStep,
        BigDecimal minNotional,
        BigDecimal maxCapital,
        BigDecimal maxPositionQuantity,
        Integer maxOrdersPerDay,
        Integer maxOrdersPerMinute,

        /**
         * Валюта КОТИРОВКИ — те деньги, в которых ведётся вся книга бота: цена, бюджет,
         * P/L, комиссия после пересчёта.
         *
         * Раньше валюту книги брали из комиссии ордера. У T-Invest это совпадало
         * случайно — комиссия рублёвая, котировка рублёвая. На Poloniex комиссия покупки
         * берётся МОНЕТОЙ, и книга подписывалась «DOGE», хотя все суммы в ней USDT.
         * Портя не только подпись: по этой валюте портфель решает, складывать ли P/L
         * ботов между собой, и два бота одного подключения переставали суммироваться.
         */
        String quoteCurrency
) {

    /** Прежняя форма — только для тестов, которым валюта книги безразлична. */
    public BotExecutionContext(UUID botId, UUID connectionId, AccountId accountId,
                               InstrumentId instrumentId, boolean dryRun,
                               BigDecimal exchangeLotSize, BigDecimal quantityStep,
                               BigDecimal minNotional, BigDecimal maxCapital,
                               BigDecimal maxPositionQuantity,
                               Integer maxOrdersPerDay, Integer maxOrdersPerMinute) {
        this(botId, connectionId, accountId, instrumentId, dryRun, exchangeLotSize, quantityStep,
                minNotional, maxCapital, maxPositionQuantity, maxOrdersPerDay, maxOrdersPerMinute, null);
    }

    public BotExecutionContext {
        if (exchangeLotSize == null || exchangeLotSize.signum() <= 0) {
            exchangeLotSize = BigDecimal.ONE;
        }
        if (quantityStep == null || quantityStep.signum() <= 0) {
            // Без явного шага считаем, что мельче заявочной единицы биржи дробить нельзя.
            quantityStep = exchangeLotSize;
        }
    }

    /**
     * Количество, приведённое к заявочным единицам биржи.
     *
     * Ровно тот пересчёт, который раньше делал sharesToLots, только в обратную сторону
     * и в единственном месте — на границе с биржей. Внутрь домена лоты не попадают.
     */
    public BigDecimal toExchangeUnits(BigDecimal quantity) {
        if (quantity == null) {
            return null;
        }
        return quantity.divide(exchangeLotSize, 10, RoundingMode.DOWN).stripTrailingZeros();
    }

    /** Обратный пересчёт: биржа ответила заявочными единицами, домену нужны базовые. */
    public BigDecimal fromExchangeUnits(BigDecimal exchangeUnits) {
        if (exchangeUnits == null) {
            return null;
        }
        return exchangeUnits.multiply(exchangeLotSize);
    }

    /**
     * Количество, округлённое ВНИЗ до торгуемого шага.
     *
     * Вниз, а не к ближайшему: округление вверх означало бы заявку чуть больше той,
     * что обеспечена бюджетом и разрешена риск-контролем.
     */
    public BigDecimal quantizeDown(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal steps = quantity.divide(quantityStep, 0, RoundingMode.DOWN);
        return steps.multiply(quantityStep);
    }
}
