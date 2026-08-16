package ru.larionov.backend.service;

import org.junit.jupiter.api.Test;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.CarryFeeSchedule;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CarryFeeResolverTest {

    private final ExchangeConnectionRepository repo = mock(ExchangeConnectionRepository.class);
    private final CarryFeeResolver resolver = new CarryFeeResolver(repo, new ObjectMapper());

    @Test
    void tInvestWithoutExplicitCarryFeeUsesSoftDefault() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(connection(id, ExchangeType.T_INVEST,
                """
                {"commissionRate":0.0005,"marginEnabled":true}
                """)));

        assertThat(resolver.dailyRate(id))
                .isEqualByComparingTo(CarryFeeResolver.T_INVEST_DEFAULT_DAILY_RATE);
    }

    @Test
    void tInvestLegacyGenericDefaultIsTreatedAsImplicit() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(connection(id, ExchangeType.T_INVEST,
                """
                {"commissionRate":0.0005,"uncoveredCarryFee":{"tiers":[],"defaultDailyRate":0.0007}}
                """)));

        assertThat(resolver.dailyRate(id))
                .isEqualByComparingTo(CarryFeeResolver.T_INVEST_DEFAULT_DAILY_RATE);
    }

    @Test
    void explicitTInvestCarryFeeWinsOverSoftDefault() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(connection(id, ExchangeType.T_INVEST,
                """
                {"commissionRate":0.0005,"uncoveredCarryFee":{"tiers":[],"defaultDailyRate":0.002}}
                """)));

        assertThat(resolver.dailyRate(id)).isEqualByComparingTo("0.002");
    }

    @Test
    void otherExchangesKeepTheGenericDefault() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(connection(id, ExchangeType.POLONIEX,
                """
                {"commissionRate":0.0005,"marginEnabled":true}
                """)));

        assertThat(resolver.dailyRate(id))
                .isEqualByComparingTo(CarryFeeSchedule.DEFAULT_DAILY_RATE);
    }

    private ExchangeConnectionEntity connection(UUID id, ExchangeType exchange, String settings) {
        return ExchangeConnectionEntity.builder()
                .id(id)
                .exchange(exchange)
                .name("test")
                .settings(settings)
                .build();
    }
}
