package ru.larionov.backend.dto;

import java.util.UUID;

public record GridPreviewRequest(
        UUID exchangeConnectionId,
        String strategyConfig
) {
}
