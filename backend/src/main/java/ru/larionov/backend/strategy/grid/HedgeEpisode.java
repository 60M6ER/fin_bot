package ru.larionov.backend.strategy.grid;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 * @param trailingTarget подтянутая за ценой цель храповика. Null — храповик ещё не
 *                     срабатывал, и действует расчётная {@code targetPrice}. Поле
 *                     переживает рестарт вместе с эпизодом; потеряй мы его, цель
 *                     вернулась бы к безубытку — не опасно, но всю набранную
 *                     прибыль эпизода это бы отдало обратно
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
        BigDecimal stopPrice,
        BigDecimal trailingTarget
) {

    /** Эпизод без храповика: заведённый до появления трейлинга или в режиме безубытка. */
    public HedgeEpisode(UUID episodeId, GridDirection direction, Instant openedAt,
                        BigDecimal entryPrice, BigDecimal hedgeQuantity, BigDecimal targetPrice,
                        BigDecimal multiplier, BigDecimal lossAtEntry, Instant deadline,
                        BigDecimal stopPrice) {
        this(episodeId, direction, openedAt, entryPrice, hedgeQuantity, targetPrice,
                multiplier, lossAtEntry, deadline, stopPrice, null);
    }

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

    /**
     * Цена, по которой эпизод закрывается СЕЙЧАС.
     *
     * Пока храповик не взведён — расчётный безубыток, и до него ещё надо ДОЙТИ.
     * После — подтянутая цель, и до неё надо ОТКАТИТЬ: смысл сравнения меняется
     * на противоположный, и об этом знает {@link #targetReached(BigDecimal)}.
     */
    @JsonIgnore
    public BigDecimal effectiveTarget() {
        return trailingTarget != null ? trailingTarget : targetPrice;
    }

    /** Взведён ли храповик: цена уже уходила в прибыль дальше безубытка. */
    @JsonIgnore
    public boolean trailing() {
        return trailingTarget != null;
    }

    /**
     * Пора закрываться по цели.
     *
     * До взведения храповика это «цель достигнута»: цена дошла до безубытка, и
     * дальше держать эпизод незачем — ради этой цены он и открывался.
     *
     * После взведения — «цена откатила до подтянутой цели», и сравнение переворачивается.
     * Иначе и быть не может: подтянутая цель лежит ПОЗАДИ ушедшей цены, и трактовать
     * её по-прежнему значило бы закрывать эпизод в тот же миг, когда храповик взвёлся.
     */
    public boolean targetReached(BigDecimal price) {
        if (price == null) {
            return false;
        }
        if (trailingTarget != null) {
            return direction == GridDirection.SHORT
                    ? price.compareTo(trailingTarget) >= 0
                    : price.compareTo(trailingTarget) <= 0;
        }
        if (targetPrice == null) {
            return false;
        }
        return direction == GridDirection.SHORT
                ? price.compareTo(targetPrice) <= 0
                : price.compareTo(targetPrice) >= 0;
    }

    /**
     * Храповик: тянет цель ЗА ценой в выгодную сторону и никогда обратно.
     *
     * Смысл — не отдавать затяжное движение. Статическая цель закрывает эпизод в ноль
     * ровно там, где рынок только разогнался; подтянутая едет следом на расстоянии
     * отступа и забирает всё, что цена прошла сверх него.
     *
     * <h3>Взводится не раньше безубытка</h3>
     * Пока цена до расчётной цели не дошла, эпизод в убытке, и «защищать» там нечего:
     * подтянутая цель означала бы выход по откату НИЖЕ безубытка, то есть ровно тот
     * убыток, ради ухода от которого плечо и открывали. Поэтому храповик взводится
     * только тем тиком, которым цель достигнута, — а до тех пор эпизод ведёт стоп.
     *
     * <h3>Хуже безубытка цель не встаёт</h3>
     * Отсюда ограничение подтянутой цели безубытком: первым же тиком в прибыли
     * храповик встаёт не дальше расчётной цены, и худший исход эпизода со взведённым
     * храповиком — тот самый безубыток, который был бы и без него. Всё, что цена
     * прошла дальше, — сверх него.
     *
     * <h3>Назад не двигается никогда</h3>
     * Это не осторожность, а само определение защиты прибыли: цель, отступающая
     * вслед за откатом, — уже не защита, а надежда, и заканчивается она сроком
     * удержания.
     *
     * @param price     последняя известная цена
     * @param offsetPct доля цены, на которой цель следует за ней
     * @param minMove   насколько минимум цель обязана сдвинуться, чтобы её трогать.
     *                  Обычно шаг цены: без порога цель переписывалась бы — и сохранялась
     *                  в базу — на каждом тике идущего движения
     * @return обновлённый эпизод либо {@code this}, если двигать нечего
     */
    public HedgeEpisode trailedTo(BigDecimal price, BigDecimal offsetPct, BigDecimal minMove) {
        if (price == null || price.signum() <= 0 || targetPrice == null
                || offsetPct == null || offsetPct.signum() <= 0) {
            return this;
        }
        // Не взведён и цена ещё не в прибыли — тянуть нечего.
        if (trailingTarget == null && !targetReached(price)) {
            return this;
        }

        BigDecimal offset = price.multiply(offsetPct);
        BigDecimal candidate = direction == GridDirection.SHORT
                ? price.add(offset).min(targetPrice)
                : price.subtract(offset).max(targetPrice);
        if (candidate.signum() <= 0) {
            return this;
        }

        if (trailingTarget == null) {
            // Взводим тем же тиком, которым цель достигнута: пропустить его нельзя,
            // иначе эпизод закроется по прежней ветке и трейлинга не случится вовсе.
            return withTrailingTarget(candidate);
        }

        // Выигрыш строго в выгодную сторону: шортовому плечу цель едет вниз, длинному — вверх.
        BigDecimal gain = direction == GridDirection.SHORT
                ? trailingTarget.subtract(candidate)
                : candidate.subtract(trailingTarget);
        BigDecimal threshold = (minMove == null || minMove.signum() <= 0)
                ? BigDecimal.ZERO
                : minMove;
        if (gain.signum() <= 0 || gain.compareTo(threshold) < 0) {
            return this;
        }
        return withTrailingTarget(candidate);
    }

    private HedgeEpisode withTrailingTarget(BigDecimal target) {
        return new HedgeEpisode(episodeId, direction, openedAt, entryPrice, hedgeQuantity,
                targetPrice, multiplier, lossAtEntry, deadline, stopPrice, target);
    }
}
