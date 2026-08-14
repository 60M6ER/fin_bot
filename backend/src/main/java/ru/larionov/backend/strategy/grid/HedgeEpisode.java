package ru.larionov.backend.strategy.grid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Идущий эпизод восстановительного плеча.
 *
 * Переживает рестарт обязательно, и это не удобство, а необходимость: пока эпизод
 * открыт, на бирже висит непокрытая позиция, а в памяти — единственное знание о том,
 * по какой цене её закрывать и до какого срока держать. Забудь бот эти числа при
 * перезапуске, и позиция осталась бы без плана выхода вовсе.
 *
 * @param episodeId    свой идентификатор: им заявки эпизода отделяются от сеточных
 *                     и в журнале, и в отчёте по поколениям
 * @param direction    направление ПЛЕЧА (не закрытой позиции): лонг переворачивается
 *                     в шорт и наоборот
 * @param openedAt     когда эпизод начат — от него отсчитывается срок удержания
 * @param entryPrice   цена, по которой переворот исполнился
 * @param hedgeQuantity размер плеча по модулю: столько предстоит закрыть
 * @param targetPrice  расчётная цена выхода в ноль со всеми издержками
 * @param multiplier   множитель, с которым перевернулись
 * @param lossAtEntry  убыток, зафиксированный закрытием старой позиции. Ради него
 *                     всё и затевалось, и по нему судят, окупился ли эпизод
 * @param deadline     до какого момента держим плечо; после — закрываем по рынку
 *                     и фиксируем результат, каким бы он ни был
 * @param stopPrice    цена, при достижении которой эпизод закрывается досрочно
 *                     с убытком. Null — стоп не задан
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HedgeEpisode(
        UUID episodeId,
        GridDirection direction,
        Instant openedAt,
        BigDecimal entryPrice,
        BigDecimal hedgeQuantity,
        BigDecimal targetPrice,
        BigDecimal multiplier,
        BigDecimal lossAtEntry,
        Instant deadline,
        BigDecimal stopPrice
) {

    /** Пора ли закрывать по сроку: держать дольше согласованного нельзя. */
    public boolean expired(Instant now) {
        return deadline != null && now != null && !now.isBefore(deadline);
    }

    /**
     * Цена ушла за стоп — эпизод не удался, и это надо признать.
     *
     * Шорт останавливается ростом, лонг — падением: стоп всегда по ту сторону входа,
     * в которую мы НЕ рассчитывали.
     */
    public boolean stopped(BigDecimal price) {
        if (stopPrice == null || price == null) {
            return false;
        }
        return direction == GridDirection.SHORT
                ? price.compareTo(stopPrice) >= 0
                : price.compareTo(stopPrice) <= 0;
    }

    /** Цель достигнута: по этой цене эпизод выходит в ноль или лучше. */
    public boolean targetReached(BigDecimal price) {
        if (targetPrice == null || price == null) {
            return false;
        }
        return direction == GridDirection.SHORT
                ? price.compareTo(targetPrice) <= 0
                : price.compareTo(targetPrice) >= 0;
    }
}
