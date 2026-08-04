package ru.larionov.backend.enums;

public enum LedgerEntryType {
    TRADE_BUY(true),
    TRADE_SELL(true),
    COMMISSION_CORRECTION(true),
    CYCLE_RESULT(false),
    GRID_REPLACED(false);

    private final boolean affectsCash;

    LedgerEntryType(boolean affectsCash) {
        this.affectsCash = affectsCash;
    }

    public boolean affectsCash() {
        return affectsCash;
    }
}
