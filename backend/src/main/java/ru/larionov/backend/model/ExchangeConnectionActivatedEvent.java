package ru.larionov.backend.model;

import ru.larionov.backend.enums.ExchangeType;

import java.util.UUID;

/**
 * Подключение к бирже поднялось и прошло health-check.
 *
 * Событие, а не прямой вызов: подписчику (синхронизации справочника) нужен
 * ExchangeRuntimeService, а тот, вызывая подписчика напрямую, получил бы циклическую
 * зависимость бинов. Заодно рассылка события не знает, кто и сколько на неё подписан.
 */
public record ExchangeConnectionActivatedEvent(UUID connectionId, ExchangeType exchange) {
}
