package ru.larionov.backend.exchange.api.model.id;

public record InstrumentId(String uid,   // nullable
                           String figi   // nullable
) {
    public String primary() {
        return uid != null ? uid : figi;
    }
}
