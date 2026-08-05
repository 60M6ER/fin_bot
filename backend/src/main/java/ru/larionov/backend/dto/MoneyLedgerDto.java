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
        Long lots,
        int lotSize,
        BigDecimal price,
        BigDecimal grossAmount,
        BigDecimal commission,
        boolean commissionEstimated,
        BigDecimal amount,
        Long executedLotsCum,
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
                e.getLots(),
                e.getLotSize(),
                e.getPrice(),
                e.getGrossAmount(),
                e.getCommission(),
                e.isCommissionEstimated(),
                e.getAmount(),
                e.getExecutedLotsCum(),
                e.getCurrency(),
                e.getNote()
        );
    }
}
