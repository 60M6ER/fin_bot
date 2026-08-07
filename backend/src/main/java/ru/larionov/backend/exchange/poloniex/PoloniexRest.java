package ru.larionov.backend.exchange.poloniex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poloniex.api.client.spot.security.AuthenticationRequestInterceptor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.time.Duration;

/**
 * Транспорт до Poloniex: собирает {@link PoloniexRestApi} и выполняет вызовы.
 *
 * Здесь же единственное место, где сетевые ошибки биржи превращаются во что-то
 * осмысленное для остального кода. Возвращать null или пустой Optional на ошибку
 * нельзя: молчаливая «пустота» в ответ на запрос состояния ордера означала бы
 * «ордера нет», а это ровно то основание, по которому гейтвей считает заявку
 * непринятой и вправе выставить её заново.
 */
@Slf4j
public final class PoloniexRest implements AutoCloseable {

    /** Jackson 2 — тот, с которым собраны модели SDK. Jackson 3 приложения его не заменяет. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            // Биржа добавляет поля быстрее, чем обновляется SDK: неизвестное поле
            // не должно ронять разбор ответа, от которого зависит торговля.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final OkHttpClient http;
    private final PoloniexRestApi api;

    public PoloniexRest(String host, String apiKey, String secret) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT)
                .readTimeout(TIMEOUT)
                .writeTimeout(TIMEOUT);

        if (apiKey != null && !apiKey.isBlank() && secret != null && !secret.isBlank()) {
            // Подпись берём у SDK: она считается по методу, пути и отсортированным
            // параметрам, и ошибка в ней стоила бы доступа к счёту.
            builder.addInterceptor(new AuthenticationRequestInterceptor(apiKey, secret));
        }

        this.http = builder.build();
        this.api = new Retrofit.Builder()
                .baseUrl(host)
                .addConverterFactory(JacksonConverterFactory.create(MAPPER))
                .client(http)
                .build()
                .create(PoloniexRestApi.class);
    }

    public PoloniexRestApi api() {
        return api;
    }

    /**
     * Выполняет вызов и разворачивает ответ.
     *
     * @throws PoloniexApiException при любой неудаче — включая 4xx и 5xx
     */
    public <T> T call(String what, Call<T> call) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful()) {
                return response.body();
            }
            String body = response.errorBody() == null ? "" : response.errorBody().string();
            throw new PoloniexApiException(response.code(), what, body);
        } catch (PoloniexApiException e) {
            throw e;
        } catch (IOException e) {
            throw new PoloniexApiException(0, what, e.getMessage());
        }
    }

    /**
     * Вызов, у которого «не найдено» — законный ответ.
     *
     * Единственный такой случай — поиск ордера по нашему clientOrderId: отсутствие
     * ордера означает, что биржа его не приняла, и это информация, а не сбой.
     * Остальные ошибки по-прежнему летят исключением.
     */
    public <T> java.util.Optional<T> callAllowingNotFound(String what, Call<T> call) {
        try {
            Response<T> response = call.execute();
            if (response.isSuccessful()) {
                return java.util.Optional.ofNullable(response.body());
            }
            if (response.code() == 404) {
                return java.util.Optional.empty();
            }
            String body = response.errorBody() == null ? "" : response.errorBody().string();
            // Poloniex отвечает 200 с кодом ошибки в теле не всегда — на неизвестный
            // ордер приходит именно 404 или 21314. Второй случай ловим по тексту.
            if (body.contains("21314") || body.toLowerCase().contains("order not found")) {
                return java.util.Optional.empty();
            }
            throw new PoloniexApiException(response.code(), what, body);
        } catch (PoloniexApiException e) {
            throw e;
        } catch (IOException e) {
            throw new PoloniexApiException(0, what, e.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        } catch (Exception e) {
            log.debug("Не удалось закрыть HTTP-клиент Poloniex: {}", e.getMessage());
        }
    }

    /** Ошибка биржи с сохранённым телом ответа: без него разбор инцидента — гадание. */
    public static class PoloniexApiException extends RuntimeException {
        private final int status;

        PoloniexApiException(int status, String what, String body) {
            super("Poloniex: %s — %s%s".formatted(
                    what,
                    status == 0 ? "сеть недоступна" : "HTTP " + status,
                    body == null || body.isBlank() ? "" : (": " + body)));
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
