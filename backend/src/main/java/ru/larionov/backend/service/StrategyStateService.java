package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.BotStrategyStateEntity;
import ru.larionov.backend.repository.BotStrategyStateRepository;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.util.Optional;
import java.util.UUID;

/** Типизированное чтение и атомарная замена контрольной точки стратегии. */
@Service
@RequiredArgsConstructor
public class StrategyStateService {

    private final BotStrategyStateRepository repo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public <T> Optional<T> read(UUID botId, Class<T> type) {
        return repo.findById(botId).map(entity -> {
            try {
                return tolerantReader(type).readValue(entity.getState());
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Не удалось прочитать сохранённое состояние стратегии: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Чтение состояния, переживающее развитие схемы.
     *
     * Контрольная точка записана прошлой версией кода — иногда очень прошлой. Строгий
     * разбор превращает любое добавление поля в остановку ВСЕГО парка ботов: 07.08.2026
     * появление {@code forcedReplacement} не дало подняться ни одному боту T-Invest,
     * потому что в их сохранённых состояниях этого поля просто не было, а примитивный
     * {@code boolean} без значения Jackson читать отказывается. Бот с позицией на бирже
     * при этом не запускается вовсе — ни торговать, ни закрыться.
     *
     * Отсюда две поблажки, и обе намеренно узкие:
     * <ul>
     *   <li>отсутствующий примитив принимает значение по умолчанию — ровно то, которое
     *       подставила бы старая версия кода, не знавшая про поле;</li>
     *   <li>незнакомое поле игнорируется — так состояние переживает и УДАЛЕНИЕ поля,
     *       и откат приложения на версию назад.</li>
     * </ul>
     *
     * Что НЕ прощается: испорченный JSON и несовпадение типов. Такое состояние по-прежнему
     * роняет старт, и это правильно — молча подставить пустой диапазон боту с открытой
     * позицией значит потерять цены встречных продаж.
     *
     * Настройка живёт здесь, а не в общем ObjectMapper приложения: снаружи, в API,
     * строгость к чужому вводу нужна.
     */
    private <T> ObjectReader tolerantReader(Class<T> type) {
        return objectMapper.readerFor(type)
                .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Transactional
    public void write(UUID botId, Object state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            BotStrategyStateEntity entity = repo.findById(botId)
                    .orElseGet(() -> BotStrategyStateEntity.builder().botId(botId).build());
            entity.setState(json);
            repo.save(entity);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось сохранить состояние стратегии: " + e.getMessage(), e);
        }
    }
}
