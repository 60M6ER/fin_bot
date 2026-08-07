package ru.larionov.backend.money;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.larionov.backend.entity.FxRateEntity;
import ru.larionov.backend.repository.FxRateRepository;
import ru.larionov.backend.service.AppSettingKeys;
import ru.larionov.backend.service.AppSettingService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Курс — вещь ненадёжная по природе: сеть отвечает не всегда, а показывать деньги
 * надо всё равно. Тесты описывают именно поведение при неудачах.
 */
class FxRateServiceTest {

    private FxRateRepository repo;
    private AppSettingService settings;

    @BeforeEach
    void setUp() {
        repo = mock(FxRateRepository.class);
        settings = mock(AppSettingService.class);
        when(settings.get(any(), any())).thenAnswer(i -> i.getArgument(1));
        when(repo.findById(anyString())).thenReturn(Optional.empty());
    }

    private FxRateService service(FxRateProvider... providers) {
        return new FxRateService(List.of(providers), repo, settings);
    }

    private static FxRateProvider provider(String id, String rate) {
        FxRateProvider p = mock(FxRateProvider.class);
        when(p.id()).thenReturn(id);
        when(p.usdRub()).thenReturn(rate == null
                ? Optional.empty()
                : Optional.of(new FxRate("USD", "RUB", new BigDecimal(rate), id, Instant.now())));
        return p;
    }

    /** USDT приравнен к доллару, и ходить за этим наружу не нужно. */
    @Test
    void usdtIsPeggedToUsdWithoutAskingAnyone() {
        FxRateProvider cbr = provider(CbrFxProvider.ID, "90");
        FxRateService service = service(cbr);

        Optional<FxRate> rate = service.rate("USDT", "USD");

        assertThat(rate).isPresent();
        assertThat(rate.get().rate()).isEqualByComparingTo(BigDecimal.ONE);
        verify(cbr, never()).usdRub();
    }

    @Test
    void usdtConvertsToRublesByTheUsdRate() {
        FxRateService service = service(provider(CbrFxProvider.ID, "90"));

        assertThat(service.convert(new BigDecimal("100"), "USDT", "RUB"))
                .contains(new BigDecimal("9000.00"));
    }

    @Test
    void rublesConvertToDollarsByTheInvertedRate() {
        FxRateService service = service(provider(CbrFxProvider.ID, "90"));

        assertThat(service.convert(new BigDecimal("9000"), "RUB", "USD"))
                .contains(new BigDecimal("100.00"));
    }

    /**
     * Главное свойство: недоступный курс даёт ПУСТО, а не ноль и не единицу.
     * Ноль показал бы, что денег нет, единица — что доллар стоит рубль; оба варианта
     * хуже честного «курс неизвестен».
     */
    @Test
    void unavailableRateYieldsEmptyRatherThanAGuess() {
        FxRateService service = service(provider(CbrFxProvider.ID, null));

        assertThat(service.usdRub()).isEmpty();
        assertThat(service.convert(new BigDecimal("100"), "USD", "RUB")).isEmpty();
    }

    /** Основной источник молчит — берём запасной, а не сдаёмся. */
    @Test
    void fallsBackToAnotherProviderWhenThePreferredOneIsSilent() {
        when(settings.get(AppSettingKeys.FX_SOURCE, CbrFxProvider.ID)).thenReturn(CbrFxProvider.ID);
        FxRateService service = service(
                provider(CbrFxProvider.ID, null),
                provider(TInvestFxProvider.ID, "91.5"));

        Optional<FxRate> rate = service.usdRub();

        assertThat(rate).isPresent();
        assertThat(rate.get().rate()).isEqualByComparingTo("91.5");
        assertThat(rate.get().source())
                .as("подпись обязана назвать реальный источник, а не запрошенный")
                .isEqualTo(TInvestFxProvider.ID);
    }

    /** Настройка выбирает источник, а не порядок бинов. */
    @Test
    void preferredProviderWinsWhenBothAnswer() {
        when(settings.get(AppSettingKeys.FX_SOURCE, CbrFxProvider.ID)).thenReturn(TInvestFxProvider.ID);
        FxRateService service = service(
                provider(CbrFxProvider.ID, "90"),
                provider(TInvestFxProvider.ID, "91.5"));

        assertThat(service.usdRub().orElseThrow().rate()).isEqualByComparingTo("91.5");
    }

    /**
     * Рестарт без сети: свежего курса нет, но сохранённый есть. Показать вчерашний
     * курс с его датой — лучше, чем прочерк на месте всего баланса.
     */
    @Test
    void fallsBackToTheLastStoredRateWhenNobodyAnswers() {
        Instant yesterday = Instant.now().minusSeconds(86_400);
        when(repo.findById("USD/RUB")).thenReturn(Optional.of(FxRateEntity.builder()
                .pair("USD/RUB")
                .rate(new BigDecimal("88.25"))
                .source(CbrFxProvider.ID)
                .asOf(yesterday)
                .build()));

        FxRateService service = service(provider(CbrFxProvider.ID, null));

        Optional<FxRate> rate = service.usdRub();
        assertThat(rate).isPresent();
        assertThat(rate.get().rate()).isEqualByComparingTo("88.25");
        assertThat(rate.get().asOf())
                .as("возраст курса обязан быть виден: по нему понятно, что число вчерашнее")
                .isEqualTo(yesterday);
    }

    /** Свежий курс сохраняется, чтобы пережить рестарт. */
    @Test
    void freshRateIsRemembered() {
        FxRateService service = service(provider(CbrFxProvider.ID, "90"));

        service.usdRub();

        verify(repo).save(any(FxRateEntity.class));
    }

    /** Второй запрос за курсом в пределах TTL не ходит наружу. */
    @Test
    void repeatedCallsAreServedFromCache() {
        FxRateProvider cbr = provider(CbrFxProvider.ID, "90");
        FxRateService service = service(cbr);

        service.usdRub();
        service.usdRub();
        service.usdRub();

        verify(cbr, org.mockito.Mockito.times(1)).usdRub();
    }

    @Test
    void unknownCrossRateIsNotInvented() {
        FxRateService service = service(provider(CbrFxProvider.ID, "90"));

        assertThat(service.rate("EUR", "RUB")).isEmpty();
    }
}
