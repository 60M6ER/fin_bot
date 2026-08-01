package ru.larionov.backend.dto;

import ru.larionov.backend.exchange.api.model.stream.StreamHealth;

/**
 * Живость стримов подключения.
 *
 * Молчащий стрим внешне неотличим от спокойного рынка, поэтому в UI важны не только
 * «подключён/нет», но и время последнего события и число переподключений: каждое
 * переподключение — это промежуток, за который события потерялись.
 */
public record ConnectionStreamsDto(
        boolean supported,
        StreamHealth marketData,
        StreamHealth orders
) {}
