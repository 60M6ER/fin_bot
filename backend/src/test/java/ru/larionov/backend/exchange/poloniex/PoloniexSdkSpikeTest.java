package ru.larionov.backend.exchange.poloniex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.OrderBook;
import com.poloniex.api.client.spot.model.response.spot.Price;
import com.poloniex.api.client.spot.rest.spot.SpotPoloRestClient;
import com.poloniex.api.client.spot.security.AuthenticationRequestInterceptor;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ручная проверка связки с Poloniex. Ходит в интернет, поэтому отключён: в обычном
 * прогоне он падал бы на любой машине без сети и делал бы набор недостоверным.
 * Запускать руками, когда нужно понять, не изменилось ли что-то на стороне биржи:
 * {@code sh gradlew test --tests '*PoloniexSdkSpikeTest*' -PskipFrontend=true}
 * (снять {@code @Disabled}).
 *
 * Зафиксировал три факта, на которых стоит адаптер:
 * <ol>
 *   <li>SDK, поднятый бомом Spring Boot 4 до Jackson 2.20, живой — модели разбираются;</li>
 *   <li>его собственные пути к справочнику ПРОТУХЛИ и отдают 404, чинить их нельзя —
 *       они в аннотациях retrofit, то есть в байт-коде;</li>
 *   <li>обход рабочий: свой retrofit-интерфейс поверх моделей и подписи SDK;</li>
 *   <li>SDK работает на ПОДНЯТЫХ okhttp 4.12 и okio 3.9 — и REST, и вебсокеты,
 *       хотя сам собран против okhttp 3.12 (2018).</li>
 * </ol>
 */
@Disabled("ходит в интернет; запускать вручную при разборе проблем с Poloniex")
class PoloniexSdkSpikeTest {

    private static final String HOST = "https://api.poloniex.com";

    /**
     * Пути SDK устарели: константы MARKETS и CURRENCIES заканчиваются слэшем,
     * а шлюз Poloniex сегодня отвечает на них 404. Поправить их нельзя — они
     * зашиты в аннотации retrofit, то есть в байт-код.
     */
    @Test
    void sdkCatalogEndpointIsBroken() {
        SpotPoloRestClient client = new SpotPoloRestClient(HOST);
        Exception e = assertThrows(Exception.class, client::getMarkets);
        System.out.println("SDK getMarkets(): " + e.getMessage().replaceAll("\\s+", " "));
        assertTrue(e.getMessage().contains("404"), "ожидали 404 от /markets/");
    }

    /** Обход: свой интерфейс с верными путями поверх моделей SDK. */
    @Test
    void ownInterfaceOverSdkModelsWorks() throws Exception {
        PoloSpike api = new Retrofit.Builder()
                .baseUrl(HOST)
                .addConverterFactory(JacksonConverterFactory.create(new ObjectMapper()))
                .client(new OkHttpClient.Builder().build())
                .build()
                .create(PoloSpike.class);

        List<Market> markets = body(api.markets());
        assertFalse(markets.isEmpty(), "справочник рынков пуст");

        Market btc = markets.stream()
                .filter(m -> "BTC_USDT".equals(m.getSymbol()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BTC_USDT не найден"));

        System.out.println("markets: " + markets.size());
        System.out.println("BTC_USDT: base=" + btc.getBaseCurrencyName()
                + " quote=" + btc.getQuoteCurrencyName()
                + " state=" + btc.getState()
                + " priceScale=" + btc.getSymbolTradeLimit().getPriceScale()
                + " quantityScale=" + btc.getSymbolTradeLimit().getQuantityScale()
                + " amountScale=" + btc.getSymbolTradeLimit().getAmountScale()
                + " minQuantity=" + btc.getSymbolTradeLimit().getMinQuantity()
                + " minAmount=" + btc.getSymbolTradeLimit().getMinAmount());

        assertNotNull(btc.getSymbolTradeLimit().getQuantityScale());
        assertNotNull(btc.getSymbolTradeLimit().getMinAmount());

        Price price = body(api.price("BTC_USDT"));
        System.out.println("price: " + price.getPrice());
        assertNotNull(price.getPrice());

        OrderBook book = body(api.orderBook("BTC_USDT", 5));
        assertFalse(book.getBids().isEmpty(), "стакан без бидов");
        System.out.println("book bids: " + book.getBids());

        List<List<String>> candles = body(api.candles("BTC_USDT", "HOUR_1", 3));
        System.out.println("candles: " + candles.size() + " first=" + candles.get(0));
        assertFalse(candles.isEmpty());
    }

    /** Подпись SDK переиспользуема: интерцептор публичный, его и берём. */
    @Test
    void sdkSigningIsReusable() throws Exception {
        AuthenticationRequestInterceptor interceptor =
                new AuthenticationRequestInterceptor("key", "secret");
        String payload = interceptor.generateSignaturePayload(
                "GET",
                okhttp3.HttpUrl.get(HOST + "/orders?symbol=BTC_USDT"),
                "1700000000000",
                null);
        System.out.println("signature payload: " + payload.replace("\n", "\\n"));
        assertEquals("GET\n/orders\nsignTimestamp=1700000000000&symbol=BTC_USDT", payload);
    }

    /**
     * Вебсокеты SDK — единственная его часть, которую мы используем целиком.
     * Проверяем, что они живы на поднятом okhttp 4: подключение публичное,
     * без ключей и без единой заявки.
     */
    @Test
    void sdkWebsocketWorksOnUpgradedOkhttp() throws Exception {
        var client = new com.poloniex.api.client.spot.ws.spot.SpotPoloPublicWebsocketClient(
                "wss://ws.poloniex.com/ws/public");
        var received = new java.util.concurrent.CountDownLatch(1);
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();

        var socket = client.onTickerEvent(List.of("BTC_USDT"),
                new com.poloniex.api.client.spot.ws.PoloApiCallback<
                        com.poloniex.api.client.spot.model.event.spot.TickerEvent>() {
                    @Override
                    public void onResponse(com.poloniex.api.client.spot.model.event.spot.PoloEvent<
                            com.poloniex.api.client.spot.model.event.spot.TickerEvent> event) {
                        if (event != null && event.getData() != null && !event.getData().isEmpty()) {
                            seen.set(event.getData().get(0).getSymbol()
                                    + " close=" + event.getData().get(0).getClose());
                            received.countDown();
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        failure.set(t);
                        received.countDown();
                    }
                });

        boolean got = received.await(30, java.util.concurrent.TimeUnit.SECONDS);
        client.close(socket);

        System.out.println("ws ticker: " + seen.get() + ", ошибка: " + failure.get());
        assertNull(failure.get(), "вебсокет упал: " + failure.get());
        assertTrue(got, "за 30 секунд не пришло ни одного события тикера");
        assertNotNull(seen.get());
    }

    private static <T> T body(Call<T> call) throws Exception {
        Response<T> response = call.execute();
        if (!response.isSuccessful()) {
            throw new AssertionError(response.code() + ": "
                    + (response.errorBody() == null ? "" : response.errorBody().string()));
        }
        return response.body();
    }

    interface PoloSpike {
        @GET("/markets")
        Call<List<Market>> markets();

        @GET("/markets/{symbol}/price")
        Call<Price> price(@Path("symbol") String symbol);

        @GET("/markets/{symbol}/orderBook")
        Call<OrderBook> orderBook(@Path("symbol") String symbol, @Query("limit") Integer limit);

        @GET("/markets/{symbol}/candles")
        Call<List<List<String>>> candles(@Path("symbol") String symbol,
                                         @Query("interval") String interval,
                                         @Query("limit") Integer limit);
    }
}
