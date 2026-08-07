package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.dto.GridPreviewDto;
import ru.larionov.backend.dto.GridPreviewRequest;
import ru.larionov.backend.exchange.api.ExchangeClient;
import ru.larionov.backend.exchange.api.model.FeeInfo;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.instrument.TradingConstraints;
import ru.larionov.backend.strategy.BotRuntimeConfig;
import ru.larionov.backend.strategy.grid.GridConfig;
import ru.larionov.backend.strategy.grid.GridLadder;
import ru.larionov.backend.strategy.grid.GridRange;
import ru.larionov.backend.strategy.grid.GridSizing;
import ru.larionov.backend.strategy.grid.GridValidator;
import ru.larionov.backend.strategy.grid.VolatilityRangeEstimator;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

/** Строит предпросмотр ровно теми же классами и данными, что и работающая стратегия. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridPreviewService {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final ExchangeRuntimeService exchangeRuntimeService;
    private final AccountCashService accountCash;
    private final ObjectMapper objectMapper;

    public GridPreviewDto preview(GridPreviewRequest request) {
        try {
            return build(request);
        } catch (Exception e) {
            return GridPreviewDto.error(message(e));
        }
    }

    private GridPreviewDto build(GridPreviewRequest request) throws Exception {
        if (request == null || request.exchangeConnectionId() == null) {
            throw new IllegalArgumentException("Выберите подключение для проверки сетки");
        }
        String json = request.strategyConfig();
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Конфигурация GRID не задана");
        }

        BotRuntimeConfig runtimeConfig = objectMapper.readValue(json, BotRuntimeConfig.class);
        GridConfig config = objectMapper.readValue(json, GridConfig.class);
        if (!runtimeConfig.hasInstrument()) {
            throw new IllegalArgumentException("Укажите инструмент для проверки сетки");
        }

        ExchangeHandler handler = exchangeRuntimeService.get(request.exchangeConnectionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Подключение не активно. Запустите его, чтобы проверить сетку по данным биржи."));
        ExchangeClient client = handler.client();
        InstrumentId instrumentId = new InstrumentId(runtimeConfig.instrumentUid(), null);
        TradingConstraints constraints = client.instruments().getConstraints(instrumentId);
        FeeInfo fees = client.fees().getFeeInfo(handler.tradingAccountId(), instrumentId);

        Instant now = Instant.now();
        GridRange range;
        BigDecimal referencePrice = null;
        BigDecimal atr = null;
        Integer atrCandlesUsed = null;
        if (config.autoRange()) {
            VolatilityRangeEstimator.Estimate estimate = new VolatilityRangeEstimator().estimate(
                    client.marketData(), instrumentId, config,
                    constraints.minPriceIncrement(), now);
            range = estimate.range();
            referencePrice = estimate.referencePrice();
            atr = estimate.atr().value();
            atrCandlesUsed = estimate.atr().candlesUsed();
        } else {
            range = GridRange.manual(config, now);
        }

        GridLadder ladder = GridLadder.build(range, constraints.minPriceIncrement());
        // Предпросмотр показывает размер при ЗАДАННОМ бюджете: реинвестированную прибыль
        // бот подмешает при старте, здесь её ещё неоткуда взять.
        GridValidator.Economics economics = GridValidator.validate(
                config, range, ladder, constraints.minPriceIncrement(), fees,
                constraints.quantityStep(), runtimeConfig.maxCapital(), config.workingBudget(() -> BigDecimal.ZERO));
        GridSizing sizing = economics.sizing();

        BigDecimal availableCash = accountCash.available(
                request.exchangeConnectionId(), constraints.quoteCurrency());

        return new GridPreviewDto(
                true, null, range.lower(), range.upper(), range.origin(),
                referencePrice, atr, atrCandlesUsed, ladder.prices(), economics.effectiveStep(),
                percent(economics.stepRate()), percent(economics.buyFeeRate()),
                percent(economics.sellFeeRate()), percent(economics.roundTripFeeRate()),
                percent(economics.requiredStepRate()), economics.commissionCoverageRatio(),
                percent(economics.netPerCycleRate()), economics.worstCaseCapital(),
                constraints.quantityStep(), constraints.minPriceIncrement(),
                sizing.quantityByLevel(), sizing.mode().name(), sizing.workingBudget(),
                sizing.budgetLeftover(), availableCash, constraints.quoteCurrency());
    }


    private static BigDecimal percent(BigDecimal rate) {
        return rate == null ? null : rate.multiply(ONE_HUNDRED);
    }

    private static String message(Exception error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
