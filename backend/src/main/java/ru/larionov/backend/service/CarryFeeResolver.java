package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.exchange.api.model.CarryFeeSchedule;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Тариф переноса непокрытой позиции по подключению.
 *
 * Отдельным местом, потому что читателей у него двое и они очень разные: проверка
 * экономики сетки при старте бота и суточное списание. Две копии разбора настроек
 * означали бы два ответа на один вопрос, и разошлись бы они не сразу, а когда-нибудь
 * потом — то есть в самый неудобный момент.
 *
 * При любой неясности возвращает консервативное умолчание, а не ноль: забытый или
 * нечитаемый тариф обязан делать бота осторожнее. Ноль здесь означал бы «удержание
 * бесплатно», то есть ровно то допущение, из-за которого шортовая сетка и оказывается
 * убыточной незаметно для владельца.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarryFeeResolver {

    private final ExchangeConnectionRepository connectionRepo;
    private final ObjectMapper objectMapper;

    public CarryFeeSchedule schedule(UUID connectionId) {
        CarryFeeSchedule schedule = settings(connectionId).uncoveredCarryFee();
        return schedule == null ? CarryFeeSchedule.defaults() : schedule;
    }

    /**
     * Разрешает ли подключение маржинальную торговлю своим ботам.
     *
     * Первый из двух уровней рубильника. При любой неясности — запрещает: право
     * рисковать даётся явно, а не достаётся по умолчанию тому, чьи настройки
     * не удалось прочитать.
     */
    public boolean marginEnabled(UUID connectionId) {
        return Boolean.TRUE.equals(settings(connectionId).marginEnabled());
    }

    /** Настройки подключения; при любой неясности — умолчания, а не исключение. */
    public ExchangeConnectionSettings settings(UUID connectionId) {
        if (connectionId == null) {
            return ExchangeConnectionSettings.defaults();
        }
        return connectionRepo.findById(connectionId)
                .map(connection -> {
                    String json = connection.getSettings();
                    if (json == null || json.isBlank()) {
                        return ExchangeConnectionSettings.defaults();
                    }
                    try {
                        return objectMapper.readValue(json, ExchangeConnectionSettings.class);
                    } catch (Exception e) {
                        log.debug("Не удалось разобрать настройки подключения {}: {}",
                                connectionId, e.getMessage());
                        return ExchangeConnectionSettings.defaults();
                    }
                })
                .orElseGet(ExchangeConnectionSettings::defaults);
    }

    /**
     * Суточная ставка без привязки к размеру позиции.
     *
     * Нужна проверке экономики сетки: там позиции ещё нет, а ступень тарифа выбирается
     * по её размеру. Берём ставку по умолчанию — она в тарифе самая невыгодная,
     * и ошибиться на ней можно только в сторону осторожности.
     */
    public BigDecimal dailyRate(UUID connectionId) {
        return schedule(connectionId).defaultDailyRate();
    }
}
