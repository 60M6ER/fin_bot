package ru.larionov.backend.exchange.tinvest;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import ru.ttech.piapi.core.connector.exception.ServiceRuntimeException;

/**
 * Разбор ошибок T-Invest сквозь обёртки SDK.
 *
 * Зачем отдельный класс: {@code SyncStubWrapper.callSyncMethod} оборачивает ЛЮБОЙ
 * Throwable в {@link ServiceRuntimeException}, поэтому обычный
 * {@code catch (StatusRuntimeException e)} вокруг вызова стаба не срабатывает никогда.
 *
 * Это стоило торгового дня: NOT_FOUND на запросе состояния ордера должен был означать
 * «биржа такого ордера не знает, он не был принят», а вместо этого улетал наверх
 * исключением. Записи навсегда оставались в PENDING, сверка гоняла их по кругу,
 * ретрай умножал каждый запрос на пять, а circuit breaker уходил в OPEN — и боты
 * переставали выставлять заявки вообще.
 */
public final class TInvestErrors {

    /** Ошибка бизнес-уровня: повторять её бессмысленно, ответ не изменится. */
    private static final int MAX_UNWRAP_DEPTH = 8;

    private TInvestErrors() {
    }

    /**
     * Код gRPC-статуса из исключения любой глубины вложенности.
     * Никогда не null: для не-gRPC ошибок это {@link Status.Code#UNKNOWN}.
     */
    public static Status.Code codeOf(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_UNWRAP_DEPTH; depth++) {
            if (current instanceof ServiceRuntimeException sre) {
                Status status = sre.getErrorStatus();
                if (status != null && status.getCode() != Status.Code.UNKNOWN) {
                    return status.getCode();
                }
            }
            if (current instanceof StatusRuntimeException sre) {
                return sre.getStatus().getCode();
            }
            if (current instanceof StatusException se) {
                return se.getStatus().getCode();
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return Status.Code.UNKNOWN;
    }

    public static boolean is(Throwable t, Status.Code code) {
        return codeOf(t) == code;
    }

    public static boolean isNotFound(Throwable t) {
        return is(t, Status.Code.NOT_FOUND);
    }

    /**
     * Стоит ли повторять вызов.
     *
     * Повторяем только то, что действительно может пройти со второй попытки:
     * перегрузку, недоступность и внутреннюю ошибку 70001 — ровно те коды, которые
     * считает повторяемыми сам SDK. Всё остальное (NOT_FOUND, INVALID_ARGUMENT,
     * отказ риск-контроля брокера) — окончательный ответ биржи.
     */
    public static boolean isRetryable(Throwable t) {
        Status.Code code = codeOf(t);
        if (code == Status.Code.RESOURCE_EXHAUSTED || code == Status.Code.UNAVAILABLE) {
            return true;
        }
        if (code != Status.Code.INTERNAL) {
            return false;
        }
        // INTERNAL повторяем только с кодом 70001: остальные внутренние ошибки
        // повторной попыткой не лечатся.
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_UNWRAP_DEPTH; depth++) {
            if (current instanceof ServiceRuntimeException sre) {
                try {
                    return sre.parseErrorCode() == 70001;
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    /**
     * Считать ли отказ поломкой сервиса для circuit breaker.
     *
     * Бизнес-ответ биржи — не поломка. Иначе достаточно нескольких запросов о
     * несуществующем ордере, чтобы разомкнуть цепь и лишить бота ВСЕХ вызовов метода,
     * включая рабочие. Ровно так и произошло с GetOrderState.
     */
    public static boolean isServiceFailure(Throwable t) {
        return isRetryable(t) || codeOf(t) == Status.Code.DEADLINE_EXCEEDED;
    }
}
