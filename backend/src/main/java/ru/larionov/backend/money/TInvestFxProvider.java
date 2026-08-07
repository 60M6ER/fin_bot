package ru.larionov.backend.money;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.larionov.backend.entity.InstrumentEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.model.id.InstrumentId;
import ru.larionov.backend.exchange.api.model.market.LastPrice;
import ru.larionov.backend.repository.InstrumentRepository;
import ru.larionov.backend.service.ExchangeHandler;
import ru.larionov.backend.service.ExchangeRuntimeService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Биржевой курс доллара к рублю — последняя цена валютной пары на бирже.
 *
 * Ближе к правде, чем курс ЦБ: это цена, по которой доллар реально меняли минуту
 * назад, а не вчерашний ориентир регулятора. Зато требует поднятого подключения
 * к T-Invest и наполненного справочника — когда их нет, честно отвечает «не знаю»,
 * и {@link FxRateService} переходит к запасному источнику.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TInvestFxProvider implements FxRateProvider {

    public static final String ID = "T_INVEST";

    /**
     * Тикеры пары USD/RUB. TOM — расчёты «завтра», самый ликвидный режим; TMS —
     * расчёты сегодня, берём как запасной. Оба означают одну и ту же валютную пару.
     */
    private static final List<String> USD_RUB_TICKERS = List.of("USD000UTSTOM", "USD000000TOD");

    private final ExchangeRuntimeService runtimeService;
    private final InstrumentRepository instrumentRepo;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Optional<FxRate> usdRub() {
        Optional<ExchangeHandler> handler = runtimeService.findRunningByExchange(ExchangeType.T_INVEST);
        if (handler.isEmpty()) {
            log.debug("Биржевой курс недоступен: нет поднятого подключения к T-Invest");
            return Optional.empty();
        }

        Optional<InstrumentEntity> pair = findPair();
        if (pair.isEmpty()) {
            log.debug("Биржевой курс недоступен: пара USD/RUB не найдена в справочнике");
            return Optional.empty();
        }

        try {
            LastPrice price = handler.get().client().marketData()
                    .getLastPrice(new InstrumentId(pair.get().getInstrumentUid(), pair.get().getFigi()));
            if (price == null || price.price() == null || price.price().value() == null) {
                return Optional.empty();
            }
            BigDecimal value = price.price().value();
            if (value.signum() <= 0) {
                return Optional.empty();
            }
            Instant asOf = price.ts() == null ? Instant.now() : price.ts();
            return Optional.of(new FxRate(CurrencyCode.USD, CurrencyCode.RUB, value, ID, asOf));
        } catch (Exception e) {
            log.debug("Не удалось получить биржевой курс USD/RUB: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<InstrumentEntity> findPair() {
        for (String ticker : USD_RUB_TICKERS) {
            Optional<InstrumentEntity> found = instrumentRepo
                    .findFirstByExchangeAndTickerAndActiveTrue(ExchangeType.T_INVEST, ticker);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
