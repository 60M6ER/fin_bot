package ru.larionov.backend.exchange.tinvest;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import ru.ttech.piapi.core.connector.exception.ServiceRuntimeException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия на корень отказа, остановившего торговлю.
 *
 * {@code SyncStubWrapper.callSyncMethod} оборачивает любой Throwable в
 * {@link ServiceRuntimeException}, поэтому {@code catch (StatusRuntimeException e)}
 * вокруг вызова стаба не срабатывает никогда. NOT_FOUND переставал быть ответом
 * «такого ордера нет» и становился отказом, а дальше ретрай множил его на пять,
 * circuit breaker размыкал метод целиком, и боты переставали торговать.
 */
class TInvestErrorsTest {

    private static ServiceRuntimeException wrapped(Status status) {
        return new ServiceRuntimeException(
                new StatusRuntimeException(status, new Metadata()));
    }

    @Test
    void notFoundIsRecognisedThroughTheSdkWrapper() {
        assertThat(TInvestErrors.isNotFound(wrapped(Status.NOT_FOUND)))
                .as("Ровно этот случай и делал записи вечно висящими в PENDING")
                .isTrue();
        assertThat(TInvestErrors.codeOf(wrapped(Status.NOT_FOUND)))
                .isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void bareGrpcExceptionIsStillRecognised() {
        assertThat(TInvestErrors.isNotFound(new StatusRuntimeException(Status.NOT_FOUND))).isTrue();
    }

    @Test
    void businessAnswersAreNotRetried() {
        assertThat(TInvestErrors.isRetryable(wrapped(Status.NOT_FOUND))).isFalse();
        assertThat(TInvestErrors.isRetryable(wrapped(Status.INVALID_ARGUMENT))).isFalse();
        assertThat(TInvestErrors.isRetryable(wrapped(Status.PERMISSION_DENIED))).isFalse();
    }

    @Test
    void infrastructureFailuresAreRetried() {
        assertThat(TInvestErrors.isRetryable(wrapped(Status.UNAVAILABLE))).isTrue();
        assertThat(TInvestErrors.isRetryable(wrapped(Status.RESOURCE_EXHAUSTED))).isTrue();
    }

    /**
     * Отказ уже разомкнутой цепи не должен повторяться: иначе каждый вызов стоит
     * maxAttempts × waitDuration впустую, а поток бота у нас один на бота.
     */
    @Test
    void circuitBreakerRejectionIsNotRetried() {
        var rejection = io.github.resilience4j.circuitbreaker.CallNotPermittedException
                .createCallNotPermittedException(
                        io.github.resilience4j.circuitbreaker.CircuitBreaker
                                .ofDefaults("OrdersService/GetOrderState"));

        assertThat(TInvestErrors.isRetryable(rejection)).isFalse();
    }

    @Test
    void businessAnswersDoNotCountAsServiceFailure() {
        assertThat(TInvestErrors.isServiceFailure(wrapped(Status.NOT_FOUND)))
                .as("Иначе несколько вопросов о несуществующем ордере размыкают метод для всех")
                .isFalse();
        assertThat(TInvestErrors.isServiceFailure(wrapped(Status.UNAVAILABLE))).isTrue();
    }
}
