package ru.larionov.backend.service;

import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionContext;

/**
 * Создатель хендлера одной биржи.
 *
 * Появился вместо условия {@code if (exchange != T_INVEST) throw} в
 * {@link ExchangeRuntimeService}: добавление биржи не должно требовать правки
 * рантайма, который управляет жизненным циклом ВСЕХ подключений.
 *
 * Реализации получают уже разрешённый контекст — секреты расшифрованы, настройки
 * разобраны, — и про JPA с шифрованием не знают.
 */
public interface ExchangeHandlerFactory {

    ExchangeType exchange();

    ExchangeHandler create(ExchangeConnectionContext context);
}
