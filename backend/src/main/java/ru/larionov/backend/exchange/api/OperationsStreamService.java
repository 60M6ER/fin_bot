package ru.larionov.backend.exchange.api;

/**
 * Поток операций/позиций по счёту.
 *
 * Намеренно не реализован: позиции даёт ордерный стрим плюс REST-сверка, а отдельное
 * соединение добавило бы ещё одну точку отказа без выигрыша. Интерфейс оставлен как
 * место расширения — {@code ExchangeClient.operationsStream()} возвращает пустой Optional,
 * и вызывающий обязан это учитывать.
 */
public interface OperationsStreamService {
}
