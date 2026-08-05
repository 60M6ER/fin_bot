package ru.larionov.backend.accounting;

import java.math.BigDecimal;

record OpenLot(
        Integer gridLevel,
        long lots,
        int lotSize,
        BigDecimal costBasis
) {
    OpenLot take(long takenLots) {
        if (takenLots >= lots) {
            return this;
        }
        BigDecimal part = costBasis.multiply(BigDecimal.valueOf(takenLots))
                .divide(BigDecimal.valueOf(lots), 9, java.math.RoundingMode.HALF_UP);
        return new OpenLot(gridLevel, takenLots, lotSize, part);
    }

    OpenLot remainingAfter(long takenLots) {
        if (takenLots >= lots) {
            return null;
        }
        OpenLot taken = take(takenLots);
        return new OpenLot(gridLevel, lots - takenLots, lotSize, costBasis.subtract(taken.costBasis()));
    }
}
