package ru.larionov.backend.dto;

import java.util.UUID;

public record ExchangeUpdateNameDto(UUID id,
                                    String name) {
}
