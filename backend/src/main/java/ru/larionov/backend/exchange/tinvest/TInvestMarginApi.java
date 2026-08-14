package ru.larionov.backend.exchange.tinvest;

import lombok.RequiredArgsConstructor;
import ru.larionov.backend.exchange.api.MarginApi;
import ru.larionov.backend.exchange.api.model.account.MarginAttributes;
import ru.larionov.backend.exchange.api.model.id.AccountId;
import ru.tinkoff.piapi.contract.v1.GetMarginAttributesRequest;
import ru.tinkoff.piapi.contract.v1.GetMarginAttributesResponse;
import ru.tinkoff.piapi.contract.v1.MoneyValue;
import ru.tinkoff.piapi.contract.v1.Quotation;
import ru.tinkoff.piapi.contract.v1.UsersServiceGrpc;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Маржинальные показатели у Т-Инвестиций.
 *
 * Живёт на том же users-стабе, что и список счетов, — отдельного соединения не нужно.
 *
 * Поля читаются через {@code hasXxx}: у protobuf отсутствующее сообщение неотличимо
 * от нулевого, а ноль здесь означал бы «обеспечения нет» — то есть ровно противоположное
 * тому, что произошло на самом деле. Пусть лучше будет null, который вызывающий обязан
 * разобрать явно.
 */
@RequiredArgsConstructor
public class TInvestMarginApi implements MarginApi {

    private final TInvestExchangeClient client;

    @Override
    public MarginAttributes getMarginAttributes(AccountId accountId) {
        GetMarginAttributesRequest request = GetMarginAttributesRequest.newBuilder()
                .setAccountId(accountId.value())
                .build();

        GetMarginAttributesResponse response = client.usersStub()
                .callSyncMethod(
                        UsersServiceGrpc.getGetMarginAttributesMethod(),
                        stub -> stub.getMarginAttributes(request)
                );

        return new MarginAttributes(
                money(response.hasLiquidPortfolio() ? response.getLiquidPortfolio() : null),
                money(response.hasStartingMargin() ? response.getStartingMargin() : null),
                money(response.hasMinimalMargin() ? response.getMinimalMargin() : null),
                quotation(response.hasFundsSufficiencyLevel()
                        ? response.getFundsSufficiencyLevel() : null),
                money(response.hasAmountOfMissingFunds() ? response.getAmountOfMissingFunds() : null),
                money(response.hasCorrectedMargin() ? response.getCorrectedMargin() : null),
                currency(response),
                Instant.now()
        );
    }

    /**
     * Валюта показателей — из первого пришедшего денежного поля.
     *
     * Все они приходят в одной валюте счёта, но какие-то могут отсутствовать,
     * поэтому берём первое непустое, а не заранее выбранное.
     */
    private static String currency(GetMarginAttributesResponse response) {
        if (response.hasLiquidPortfolio() && !response.getLiquidPortfolio().getCurrency().isBlank()) {
            return response.getLiquidPortfolio().getCurrency().toUpperCase();
        }
        if (response.hasStartingMargin() && !response.getStartingMargin().getCurrency().isBlank()) {
            return response.getStartingMargin().getCurrency().toUpperCase();
        }
        if (response.hasMinimalMargin() && !response.getMinimalMargin().getCurrency().isBlank()) {
            return response.getMinimalMargin().getCurrency().toUpperCase();
        }
        return null;
    }

    private static BigDecimal money(MoneyValue value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value.getUnits()).add(BigDecimal.valueOf(value.getNano(), 9));
    }

    private static BigDecimal quotation(Quotation value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value.getUnits()).add(BigDecimal.valueOf(value.getNano(), 9));
    }
}
