package ru.larionov.backend.dto;

import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.enums.LedgerEntryType;
import ru.larionov.backend.exchange.api.enums.OrderSide;

import java.math.BigDecimal;
import java.time.Instant;

public record MoneyLedgerDto(
        Long seq,
        Instant ts,
        LedgerEntryType entryType,
        boolean affectsCash,
        String clientOrderId,
        OrderSide side,
        Integer gridLevel,
        BigDecimal quantity,
        BigDecimal exchangeLotSize,
        BigDecimal price,
        BigDecimal grossAmount,
        BigDecimal commission,
        boolean commissionEstimated,
        BigDecimal amount,
        BigDecimal executedQuantityCum,
        String currency,
        String note
) {
    public static MoneyLedgerDto of(MoneyLedgerEntity e) {
        return new MoneyLedgerDto(
                e.getSeq(),
                e.getTs(),
                e.getEntryType(),
                e.isAffectsCash(),
                e.getClientOrderId(),
                e.getSide(),
                e.getGridLevel(),
                e.getQuantity(),
                e.getExchangeLotSize(),
                e.getPrice(),
                e.getGrossAmount(),
                e.getCommission(),
                e.isCommissionEstimated(),
                e.getAmount(),
                e.getExecutedQuantityCum(),
                e.getCurrency(),
                e.getNote()
        );
    }
}
