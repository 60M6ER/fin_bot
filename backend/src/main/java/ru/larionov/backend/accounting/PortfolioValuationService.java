package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.ConnectionValuationDto;
import ru.larionov.backend.dto.PortfolioValuationDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.money.CurrencyCode;
import ru.larionov.backend.money.FxRate;
import ru.larionov.backend.money.FxRateService;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Портфель целиком: все подключения, все валюты, одна цифра сверху.
 *
 * Разбивка по валютам и сводный итог живут рядом намеренно. Разбивка достоверна
 * всегда — это просто деньги, которые есть. Итог зависит от курса и потому может
 * отсутствовать; тогда пользователь всё равно видит из чего состоит его портфель,
 * а не пустой экран.
 */
@Service
@RequiredArgsConstructor
public class PortfolioValuationService {

    private final ExchangeConnectionRepository connectionRepo;
    private final BotRepository botRepo;
    private final BotValuationService botValuation;
    private final FxRateService fx;

    @Transactional(readOnly = true)
    public PortfolioValuationDto portfolio() {
        String displayCurrency = botValuation.displayCurrency();
        List<ExchangeConnectionEntity> connections = connectionRepo.findAll();

        // Ботов забираем одним запросом и раскладываем по подключениям: иначе на
        // каждое подключение уходил бы отдельный поход в базу.
        Map<UUID, List<BotEntity>> botsByConnection = botRepo.findAll().stream()
                .filter(b -> b.getExchangeConnectionId() != null)
                .collect(Collectors.groupingBy(BotEntity::getExchangeConnectionId));

        Map<String, BigDecimal> byCurrency = new HashMap<>();
        boolean incomplete = false;
        int botCount = 0;

        for (ExchangeConnectionEntity connection : connections) {
            List<BotEntity> bots = botsByConnection.getOrDefault(connection.getId(), List.of());
            botCount += bots.size();

            ConnectionValuationDto valuation = botValuation.connectionValuation(connection.getId(), bots);
            incomplete |= valuation.incomplete();
            valuation.byCurrency().forEach((currency, amount) ->
                    byCurrency.merge(currency, amount, BigDecimal::add));
        }

        BigDecimal total = BigDecimal.ZERO;
        String fxSource = null;
        Instant fxAsOf = null;

        for (Map.Entry<String, BigDecimal> entry : byCurrency.entrySet()) {
            Optional<FxRate> rate = fx.rate(entry.getKey(), displayCurrency);
            if (rate.isEmpty()) {
                // Хоть одна валюта без курса — сводить нечем. Показываем разбивку
                // и честную пометку вместо числа, посчитанного наполовину.
                return new PortfolioValuationDto(byCurrency, null, displayCurrency,
                        connections.size(), botCount, true, null, null);
            }
            total = total.add(entry.getValue().multiply(rate.get().rate()));
            if (!"IDENTITY".equals(rate.get().source())) {
                fxSource = rate.get().source();
                fxAsOf = rate.get().asOf();
            }
        }

        return new PortfolioValuationDto(
                byCurrency,
                byCurrency.isEmpty() ? null : total.setScale(2, RoundingMode.HALF_UP),
                displayCurrency,
                connections.size(),
                botCount,
                incomplete,
                fxSource,
                fxAsOf);
    }

    /** Курс, которым сейчас считается свод. Нужен экрану настроек для проверки. */
    public Optional<FxRate> displayRate(String from) {
        return fx.rate(CurrencyCode.normalize(from), botValuation.displayCurrency());
    }
}
