package ru.larionov.backend.dto;

/** Счёт, доступный по токену подключения — для выбора в UI. */
public record ExchangeAccountDto(
        String id,
        String name,
        String type,
        boolean sandbox
) {}
