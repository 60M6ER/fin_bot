package ru.larionov.backend.dto;

import java.util.UUID;

public record ExchangeSetSandboxEnabledDto(UUID id,
                                           boolean enabled) {
}
