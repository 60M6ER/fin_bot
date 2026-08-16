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
 * @param phase         фактическая стадия биржевого исполнения. Эпизод нельзя считать
 *                      открытым по одной лишь принятой заявке и нельзя считать
 *                      закрытым, пока RECOVERY не исполнена целиком
 * @param originalQuantity размер позиции сетки до переворота. Нужен, чтобы после
 *                      исполнения HEDGE пересчитать плечо по фактической средней цене
 * @param originalCostBasis себестоимость исходной позиции до переворота
 * @param plannedEntryQuantity сколько всего должна исполнить заявка переворота
 * @param closingReason почему начат выход. Сохраняется до подтверждённого исполнения
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
        BigDecimal trailingTarget,
        Phase phase,
        BigDecimal originalQuantity,
        BigDecimal originalCostBasis,
        BigDecimal plannedEntryQuantity,
        String closingReason
) {

    public enum Phase {
        /** Заявка переворота ещё не исполнена целиком. */
        OPENING,
        /** Противоположная нога открыта и ведётся по цели, стопу и сроку. */
        ACTIVE,
        /** Выход отправлен, но фактический остаток ещё не закрыт полностью. */
        CLOSING
    }

    public HedgeEpisode {
        // Состояния, записанные до появления автомата фаз, были активными эпизодами.
        if (phase == null) {
            phase = Phase.ACTIVE;
        }
    }

    /** Эпизод без храповика: заведённый до появления трейлинга или в режиме безубытка. */
    public HedgeEpisode(UUID episodeId, GridDirection direction, Instant openedAt,
                        BigDecimal entryPrice, BigDecimal hedgeQuantity, BigDecimal targetPrice,
                        BigDecimal multiplier, BigDecimal lossAtEntry, Instant deadline,
                        BigDecimal stopPrice) {
        this(episodeId, direction, openedAt, entryPrice, hedgeQuantity, targetPrice,
                multiplier, lossAtEntry, deadline, stopPrice, null,
                Phase.ACTIVE, null, null, null, null);
    }

    /** Эпизод перед отправкой HEDGE: состояние существует раньше сетевого вызова. */
    public static HedgeEpisode opening(UUID episodeId, GridDirection direction, Instant openedAt,
                                       HedgeMath.Plan plan, BigDecimal originalQuantity,
                                       BigDecimal originalCostBasis, Instant deadline,
                                       BigDecimal stopPrice, BigDecimal plannedEntryQuantity) {
        return new HedgeEpisode(episodeId, direction, openedAt, plan.entryPrice(),
                plan.hedgeQuantity(), plan.targetPrice(),
                plan.totalQuantity().divide(originalQuantity, 12, java.math.RoundingMode.HALF_UP),
                plan.realizedOnClose(), deadline, stopPrice, null, Phase.OPENING,
                originalQuantity, originalCostBasis, plannedEntryQuantity, null);
    }

    @JsonIgnore
    public boolean opening() {
        return phase == Phase.OPENING;
    }

    @JsonIgnore
    public boolean active() {
        return phase == Phase.ACTIVE;
    }

    @JsonIgnore
    public boolean closing() {
        return phase == Phase.CLOSING;
    }

    /** Подтверждённый HEDGE: все денежные параметры заменяются фактическими. */
    public HedgeEpisode activated(HedgeMath.Plan plan, BigDecimal actualMultiplier,
                                  BigDecimal actualStopPrice) {
        return new HedgeEpisode(episodeId, direction, openedAt, plan.entryPrice(),
                plan.hedgeQuantity(), plan.targetPrice(), actualMultiplier,
                plan.realizedOnClose(), deadline, actualStopPrice, null, Phase.ACTIVE,
                originalQuantity, originalCostBasis, plannedEntryQuantity, null);
    }

    /** Решение о выходе сохранено, но эпизод остаётся жив до полного исполнения. */
    public HedgeEpisode startClosing(String reason) {
        return new HedgeEpisode(episodeId, direction, openedAt, entryPrice, hedgeQuantity,
                targetPrice, multiplier, lossAtEntry, deadline, stopPrice, trailingTarget,
                Phase.CLOSING, originalQuantity, originalCostBasis, plannedEntryQuantity, reason);
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
                targetPrice, multiplier, lossAtEntry, deadline, stopPrice, target,
                phase, originalQuantity, originalCostBasis, plannedEntryQuantity, closingReason);
    }
}
