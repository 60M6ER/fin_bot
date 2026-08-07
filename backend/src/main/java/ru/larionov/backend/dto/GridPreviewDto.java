package ru.larionov.backend.dto;

import ru.larionov.backend.strategy.grid.GridRange;

import java.math.BigDecimal;
import java.util.List;

public record GridPreviewDto(
        boolean ready,
        String error,
        BigDecimal lowerPrice,
        BigDecimal upperPrice,
        GridRange.Origin rangeOrigin,
        BigDecimal referencePrice,
        BigDecimal atr,
        Integer atrCandlesUsed,
        List<BigDecimal> ladderPrices,
        BigDecimal effectiveStep,
        BigDecimal stepPercent,
        BigDecimal buyFeePercent,
        BigDecimal sellFeePercent,
        BigDecimal roundTripFeePercent,
        BigDecimal requiredStepPercent,
        BigDecimal commissionCoverageRatio,
        BigDecimal netPerCyclePercent,
        BigDecimal worstCaseCapital,
        /** Минимальный шаг количества биржи: им ограничен размер заявки. */
        BigDecimal quantityStep,
        BigDecimal priceIncrement,
        /** Размер заявки по уровням покупки в единицах базового актива: индекс — уровень. */
        List<BigDecimal> quantityByLevel,
        String sizingMode,
        BigDecimal workingBudget,
        BigDecimal budgetLeftover,
        /** Свободные деньги счёта в валюте инструмента; null, если баланс недоступен. */
        BigDecimal availableCash,
        String cashCurrency
) {
    public GridPreviewDto {
        ladderPrices = ladderPrices == null ? List.of() : List.copyOf(ladderPrices);
        quantityByLevel = quantityByLevel == null ? List.of() : List.copyOf(quantityByLevel);
    }

    public static GridPreviewDto error(String message) {
        return new GridPreviewDto(false, message, null, null, null,
                null, null, null, List.of(), null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null);
    }
}
