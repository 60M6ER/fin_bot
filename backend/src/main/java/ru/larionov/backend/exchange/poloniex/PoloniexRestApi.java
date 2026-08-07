package ru.larionov.backend.exchange.poloniex;

import com.poloniex.api.client.spot.model.request.spot.CancelOrderRequest;
import com.poloniex.api.client.spot.model.response.spot.Account;
import com.poloniex.api.client.spot.model.response.spot.AccountBalance;
import com.poloniex.api.client.spot.model.response.spot.CanceledOrder;
import com.poloniex.api.client.spot.model.response.spot.FeeInfo;
import com.poloniex.api.client.spot.model.response.spot.Market;
import com.poloniex.api.client.spot.model.response.spot.Order;
import com.poloniex.api.client.spot.model.response.spot.OrderBook;
import com.poloniex.api.client.spot.model.response.spot.Price;
import com.poloniex.api.client.spot.model.response.spot.Trade;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

import java.util.List;
import java.util.Map;

/**
 * Свой retrofit-интерфейс к Poloniex поверх МОДЕЛЕЙ SDK.
 *
 * <h3>Зачем не сам SDK</h3>
 * У официального SDK протухли пути к справочнику: константы {@code MARKETS}
 * и {@code CURRENCIES} заканчиваются слэшем, а шлюз Poloniex сегодня отвечает на
 * {@code /markets/} ошибкой 404 (проверено, см. {@code PoloniexSdkSpikeTest}).
 * Починить их снаружи нельзя — они зашиты в аннотации retrofit, то есть в байт-код,
 * а вставить свой перехватчик некуда: генератор сервисов статический и собирает
 * OkHttpClient сам.
 *
 * Поэтому объявляем нужные вызовы сами. Модели ответов и HMAC-подпись при этом
 * берём у SDK: подпись — самая тонкая часть, и переписывать её ради стиля значило бы
 * добавить себе шанс ошибиться там, где ошибка стоит доступа к деньгам.
 *
 * Объявлены ровно те эндпоинты, которые нужны боту. Всё остальное осознанно
 * отсутствует: неиспользуемый метод к бирже — это метод, который никто не проверял.
 */
public interface PoloniexRestApi {

    // ------------------------------------------------------------------ публичные

    @GET("/markets")
    Call<List<Market>> markets();

    @GET("/markets/{symbol}")
    Call<List<Market>> market(@Path("symbol") String symbol);

    @GET("/markets/{symbol}/price")
    Call<Price> price(@Path("symbol") String symbol);

    @GET("/markets/price")
    Call<List<Price>> prices();

    @GET("/markets/{symbol}/orderBook")
    Call<OrderBook> orderBook(@Path("symbol") String symbol, @Query("limit") Integer limit);

    /**
     * Свечи приходят массивом массивов строк, а не объектом: у Poloniex это
     * позиционный формат, и SDK его тоже отдаёт сырым.
     */
    @GET("/markets/{symbol}/candles")
    Call<List<List<String>>> candles(@Path("symbol") String symbol,
                                     @Query("interval") String interval,
                                     @Query("limit") Integer limit,
                                     @Query("startTime") Long startTime,
                                     @Query("endTime") Long endTime);

    // ------------------------------------------------------------------ приватные

    @GET("/accounts")
    Call<List<Account>> accounts();

    @GET("/accounts/balances")
    Call<List<AccountBalance>> balances(@Query("accountType") String accountType);

    @GET("/feeinfo")
    Call<FeeInfo> feeInfo();

    @POST("/orders")
    Call<Order> placeOrder(@Body Map<String, Object> request);

    @GET("/orders")
    Call<List<Order>> openOrders(@Query("symbol") String symbol,
                                 @Query("side") String side,
                                 @Query("limit") Integer limit);

    @GET("/orders/{id}")
    Call<Order> orderById(@Path("id") String id);

    /** Ключевой для идемпотентности: спросить о судьбе ордера по НАШЕМУ идентификатору. */
    @GET("/orders/cid:{clientOrderId}")
    Call<Order> orderByClientOrderId(@Path("clientOrderId") String clientOrderId);

    @DELETE("/orders/{id}")
    Call<CanceledOrder> cancelById(@Path("id") String id);

    @DELETE("/orders/cid:{clientOrderId}")
    Call<CanceledOrder> cancelByClientOrderId(@Path("clientOrderId") String clientOrderId);

    @POST("/orders/cancelByIds")
    Call<List<CanceledOrder>> cancelByIds(@Body CancelOrderRequest request);

    /** Сделки по счёту: отсюда берётся ФАКТИЧЕСКАЯ комиссия и её валюта. */
    @GET("/trades")
    Call<List<Trade>> trades(@Query("limit") Integer limit,
                             @Query("startTime") Long startTime,
                             @Query("endTime") Long endTime);
}
