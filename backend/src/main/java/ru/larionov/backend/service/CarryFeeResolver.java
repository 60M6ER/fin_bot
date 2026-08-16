package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.exchange.api.model.CarryFeeSchedule;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Тариф переноса непокрытой позиции по подключению.
 *
 * Отдельным местом, потому что читателей у него двое и они очень разные: проверка
 * экономики сетки при старте бота и суточное списание. Две копии разбора настроек
 * означали бы два ответа на один вопрос, и разошлись бы они не сразу, а когда-нибудь
 * потом — то есть в самый неудобный момент.
 *
 * При любой неясности возвращает умолчание, а не ноль: забытый или нечитаемый тариф
 * не должен превращать удержание в бесплатное. Для T-Invest это мягкая оценка, потому
 * что реальный тариф переноса у брокера ступенчатый и в основном фиксированный в
 * рублях; для остальных подключений остаётся общий консервативный дефолт.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarryFeeResolver {

    /**
     * Мягкая оценка переноса T-Invest: 0.01% в сутки.
     *
     * У брокера перенос сейчас тарифицируется ступенчато и в основном фиксированной
     * суммой в рублях, поэтому превращать его в «точный процент» было бы ложной
     * точностью. Для стартовой проверки шортовой сетки это именно небольшая поправка,
     * а не модель тарифной таблицы.
     */
    public static final BigDecimal T_INVEST_DEFAULT_DAILY_RATE = new BigDecimal("0.0001");

    private final ExchangeConnectionRepository connectionRepo;
    private final ObjectMapper objectMapper;

    public CarryFeeSchedule schedule(UUID connectionId) {
        if (connectionId == null) {
            return CarryFeeSchedule.defaults();
        }
        return connectionRepo.findById(connectionId)
                .map(this::schedule)
                .orElseGet(CarryFeeSchedule::defaults);
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
                .map(connection -> readSettings(connection.getId(), connection.getSettings()))
                .orElseGet(ExchangeConnectionSettings::defaults);
    }

    /**
     * Суточная ставка без привязки к размеру позиции.
     *
     * Нужна проверке экономики сетки: там позиции ещё нет, а реальная цена переноса
     * зависит от брокерского тарифа и размера непокрытой позиции. Поэтому берём
     * ставку по умолчанию для подключения: это локальная оценка риска, а не ответ API.
     */
    public BigDecimal dailyRate(UUID connectionId) {
        return schedule(connectionId).defaultDailyRate();
    }

    private CarryFeeSchedule schedule(ExchangeConnectionEntity connection) {
        ExchangeConnectionSettings settings = readSettings(connection.getId(), connection.getSettings());
        CarryFeeSchedule schedule = settings.uncoveredCarryFee();
        if (connection.getExchange() == ExchangeType.T_INVEST
                && useSoftTInvestDefault(connection.getSettings(), schedule)) {
            return new CarryFeeSchedule(List.of(), T_INVEST_DEFAULT_DAILY_RATE);
        }
        return schedule == null ? CarryFeeSchedule.defaults() : schedule;
    }

    private ExchangeConnectionSettings readSettings(UUID connectionId, String json) {
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
    }

    /**
     * Старый UI мог сохранить в JSON дефолт, который подставил record-конструктор,
     * хотя пользователь тариф переноса вообще не настраивал. Для T-Invest такой
     * legacy-дефолт считаем отсутствующим; любой другой явно заданный тариф сохраняет
     * приоритет.
     */
    private boolean useSoftTInvestDefault(String json, CarryFeeSchedule schedule) {
        if (!hasExplicitCarryFee(json)) {
            return true;
        }
        return schedule != null
                && (schedule.tiers() == null || schedule.tiers().isEmpty())
                && CarryFeeSchedule.DEFAULT_DAILY_RATE.compareTo(schedule.defaultDailyRate()) == 0;
    }

    private boolean hasExplicitCarryFee(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            var field = objectMapper.readTree(json).get("uncoveredCarryFee");
            return field != null && !field.isNull();
        } catch (Exception e) {
            return false;
        }
    }
}
