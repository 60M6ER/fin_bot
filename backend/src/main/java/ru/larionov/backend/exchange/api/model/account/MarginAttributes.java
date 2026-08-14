package ru.larionov.backend.exchange.api.model.account;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Маржинальные показатели счёта: сколько обеспечения есть и сколько его требуется.
 *
 * Единственный источник правды о том, переживёт ли счёт непокрытую позицию. Считать
 * это самостоятельно нельзя даже приблизительно: брокер применяет свои ставки риска
 * к каждой бумаге, учитывает уже открытые позиции целиком и меняет требования в
 * течение дня. Любая наша оценка разошлась бы с его — а закрывает позицию он.
 *
 * @param liquidPortfolio       ликвидная стоимость портфеля: то, чем счёт отвечает
 * @param startingMargin        начальная маржа — сколько обеспечения нужно, чтобы
 *                              ОТКРЫТЬ имеющиеся позиции. С ней сравнивают перед сделкой
 * @param minimalMargin         минимальная маржа: опустился ниже — брокер закрывает
 *                              позицию сам, по своей цене и в свой момент
 * @param fundsSufficiencyLevel уровень достаточности средств. Меньше единицы —
 *                              маржин-колл уже наступил
 * @param amountOfMissingFunds  сколько денег не хватает прямо сейчас; больше нуля —
 *                              это уже не риск, а факт
 * @param correctedMargin       скорректированная маржа
 * @param currency              валюта показателей
 * @param at                    момент наблюдения. Свежесть здесь существенна: значения
 *                              меняются каждой сделкой, и решение по протухшим —
 *                              это решение вслепую
 */
public record MarginAttributes(
        BigDecimal liquidPortfolio,
        BigDecimal startingMargin,
        BigDecimal minimalMargin,
        BigDecimal fundsSufficiencyLevel,
        BigDecimal amountOfMissingFunds,
        BigDecimal correctedMargin,
        String currency,
        Instant at
) {

    /**
     * Свободное обеспечение: сколько ещё можно занять под новые позиции.
     *
     * @return null, если брокер не отдал одну из составляющих — считать её нулём
     *         значило бы выдать неизвестность за разрешение
     */
    public BigDecimal freeMargin() {
        if (liquidPortfolio == null || startingMargin == null) {
            return null;
        }
        return liquidPortfolio.subtract(startingMargin);
    }

    /**
     * Маржин-колл: обеспечения уже не хватает.
     *
     * Два независимых признака, и достаточно любого. Уровень достаточности — то, как
     * это видит сам брокер; сумма нехватки — то же самое в деньгах, и она бывает
     * заполнена, когда уровень почему-то не пришёл.
     */
    public boolean marginCall() {
        if (amountOfMissingFunds != null && amountOfMissingFunds.signum() > 0) {
            return true;
        }
        return fundsSufficiencyLevel != null
                && fundsSufficiencyLevel.compareTo(BigDecimal.ONE) < 0;
    }

    /**
     * Достаточность опустилась ниже порога, за которым пора закрываться самим.
     *
     * Смысл порога в том, чтобы успеть раньше брокера: он закрывает позицию по своей
     * цене, а мы — по своей. Неизвестный уровень тревогой НЕ считается: поднимать её
     * на каждом непришедшем ответе значило бы приучить к ложным срабатываниям, а
     * настоящую нехватку показывает {@link #marginCall()} отдельно.
     */
    public boolean nearStopOut(BigDecimal threshold) {
        if (fundsSufficiencyLevel == null || threshold == null) {
            return false;
        }
        return fundsSufficiencyLevel.compareTo(threshold) < 0;
    }
}
