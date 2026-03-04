package ru.larionov.backend.model;

import ru.larionov.backend.enums.RuntimeState;

import java.time.Instant;
import java.util.UUID;

public record RuntimeInfo(UUID id,
                          RuntimeState state,
                          String lastError,      // nullable
                          Instant changedAt      // когда статус обновился
) {
}
