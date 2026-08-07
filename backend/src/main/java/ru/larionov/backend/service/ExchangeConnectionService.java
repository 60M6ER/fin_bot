package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.accounting.BotValuationService;
import ru.larionov.backend.dto.*;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.exchange.api.model.ExchangeConnectionSettings;
import ru.larionov.backend.exchange.api.model.stream.StreamHealth;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;
import ru.larionov.backend.security.SecretCipher;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeConnectionService {

    private final ExchangeConnectionRepository repo;
    private final BotRepository botRepo;
    private final ExchangeRuntimeService runtimeService;
    private final ExchangeConnectionContextResolver contextResolver;
    private final SecretCipher cipher;
    private final BotValuationService valuationService;
    private final ObjectMapper objectMapper;

    public Page<ExchangeConnectionListItemDto> list(Pageable pageable) {
        Page<ExchangeConnectionEntity> page = repo.findAll(pageable);

        // Ботов забираем одним запросом и группируем в памяти: иначе оценка каждой
        // строки лезла бы в БД отдельно, а список опрашивается фронтендом постоянно.
        Map<UUID, List<BotEntity>> botsByConnection = botRepo.findAll().stream()
                .filter(b -> b.getExchangeConnectionId() != null)
                .collect(Collectors.groupingBy(BotEntity::getExchangeConnectionId));

        return page.map(e -> toListItem(
                e, botsByConnection.getOrDefault(e.getId(), List.of())));
    }

    public ExchangeConnectionDetailDto get(UUID id) {
        return toDetail(requireConnection(id));
    }

    /**
     * Биржи, которые можно выбрать при создании подключения.
     *
     * Спрашиваем у рантайма, а не перечисляем значения enum: биржа без адаптера
     * появлялась бы в выпадающем списке и падала бы только при активации.
     */
    public List<ExchangeType> getExchangeTypes() {
        return Arrays.stream(ExchangeType.values())
                .filter(runtimeService.supportedExchanges()::contains)
                .toList();
    }

    /**
     * Счета, доступные по токену. Требует поднятого подключения — список приходит
     * от биржи, а не из нашей БД.
     */
    public List<ExchangeAccountDto> listAccounts(UUID id) {
        requireConnection(id);
        ExchangeHandler handler = runtimeService.get(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Подключение не активно. Запустите его, чтобы получить список счетов."));

        return handler.client().accounts().listAccounts().stream()
                .map(a -> new ExchangeAccountDto(
                        a.id().value(),
                        a.name(),
                        a.type() == null ? null : a.type().name(),
                        a.sandbox()))
                .toList();
    }

    /** Живость стримов. Без активного подключения стримов нет — отдаём «отключено». */
    public ConnectionStreamsDto streams(UUID id) {
        requireConnection(id);
        return runtimeService.get(id)
                .map(h -> new ConnectionStreamsDto(true, h.marketDataStreamHealth(), h.ordersStreamHealth()))
                .orElseGet(() -> new ConnectionStreamsDto(
                        false, StreamHealth.disconnected(), StreamHealth.disconnected()));
    }

    @Transactional
    public UUID create(ExchangeConnectionCreateRequest req) {
        if (req.exchange() == null) {
            throw new IllegalArgumentException("exchange is required");
        }
        ExchangeConnectionEntity e = ExchangeConnectionEntity.builder()
                .exchange(req.exchange())
                .name(requireName(req.name()))
                .sandboxEnabled(false)
                .active(false)
                .settings("{}")
                .build();

        repo.save(e);
        return e.getId();
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NotFoundException("Exchange connection not found: " + id);
        }
        // FK на bot стоит с RESTRICT: без явной проверки пользователь получил бы
        // невнятную ошибку драйвера вместо объяснения.
        if (botRepo.existsByExchangeConnectionId(id)) {
            throw new IllegalStateException(
                    "К подключению привязаны боты. Удалите или перенесите их перед удалением подключения.");
        }
        repo.deleteById(id);
    }

    @Transactional
    public void updateName(ExchangeUpdateNameDto dto) {
        ExchangeConnectionEntity e = requireConnection(dto.id());
        e.setName(requireName(dto.name()));
        repo.save(e);
    }

    @Transactional
    public void setSandboxEnabled(ExchangeSetSandboxEnabledDto dto) {
        ExchangeConnectionEntity e = requireConnection(dto.id());
        requireStopped(dto.id(), "переключить песочницу");
        e.setSandboxEnabled(dto.enabled());
        repo.save(e);
    }

    @Transactional
    public void updateCredentials(ExchangeUpdateCredentialsDto dto) {
        ExchangeConnectionEntity e = requireConnection(dto.id());
        requireStopped(dto.id(), "изменить ключи");

        // Шифруем на входе: в БД секреты в открытом виде не попадают.
        e.setApiKey(cipher.encrypt(normalizeNullable(dto.apiKey())));
        e.setApiSecret(cipher.encrypt(normalizeNullable(dto.apiSecret())));
        e.setPassphrase(cipher.encrypt(normalizeNullable(dto.passphrase())));

        repo.save(e);
    }

    @Transactional
    public void updateSettings(ExchangeUpdateSettingsDto dto) {
        ExchangeConnectionEntity e = requireConnection(dto.id());
        requireStopped(dto.id(), "изменить настройки");

        e.setAccountId(normalizeNullable(dto.accountId()));

        ExchangeConnectionSettings settings =
                dto.settings() == null ? ExchangeConnectionSettings.defaults() : dto.settings();
        e.setSettings(objectMapper.writeValueAsString(settings));

        repo.save(e);
    }

    // ==============================
    // VALIDATION
    // ==============================

    private ExchangeConnectionEntity requireConnection(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));
    }

    /**
     * Менять ключи, счёт или песочницу под работающим подключением нельзя: клиент уже
     * создан со старыми значениями, и часть ботов может держать открытые ордера.
     */
    private void requireStopped(UUID id, String action) {
        if (runtimeService.isRunning(id)) {
            throw new IllegalStateException(
                    "Нельзя " + action + ": подключение активно. Сначала остановите его.");
        }
    }

    private String requireName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return raw.trim();
    }

    private String normalizeNullable(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isBlank() ? null : t;
    }

    // ==============================
    // MAPPING
    // ==============================

    private ExchangeConnectionListItemDto toListItem(ExchangeConnectionEntity e, List<BotEntity> bots) {
        // Раньше здесь был хардкод INACTIVE, из-за чего список всегда показывал
        // «выключено» независимо от реальности.
        RuntimeInfo info = runtimeService.runtimeInfo(e.getId());

        return new ExchangeConnectionListItemDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive(),
                info.state(),
                info.lastError() == null ? "" : info.lastError(),
                valuationOrEmpty(e.getId(), bots)
        );
    }

    private ExchangeConnectionDetailDto toDetail(ExchangeConnectionEntity e) {
        RuntimeInfo info = runtimeService.runtimeInfo(e.getId());

        return new ExchangeConnectionDetailDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive(),
                info.state(),
                info.lastError() == null ? "" : info.lastError(),
                e.isSandboxEnabled(),
                maskKey(safeDecrypt(e.getApiKey())),
                isPresent(e.getApiSecret()),
                isPresent(e.getPassphrase()),
                cipher.isEnabled(),
                e.getAccountId(),
                contextResolver.parseSettings(e),
                e.getCreatedAt(),
                e.getUpdatedAt(),
                valuationOrEmpty(e.getId(), botRepo.findAllByExchangeConnectionIdOrderByNameAsc(e.getId()))
        );
    }

    /**
     * Сбой оценки не должен ронять экран подключения: ключи и настройки нужны
     * пользователю именно тогда, когда что-то не работает.
     */
    private ConnectionValuationDto valuationOrEmpty(UUID connectionId, List<BotEntity> bots) {
        try {
            return valuationService.connectionValuation(connectionId, bots);
        } catch (Exception e) {
            log.warn("Не удалось оценить подключение {}: {}", connectionId, e.getMessage());
            return ConnectionValuationDto.empty();
        }
    }

    private boolean isPresent(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * Для маски достаточно знать первые и последние символы. Если расшифровать не вышло
     * (сменили APP_SECRET_KEY), экран подключения всё равно должен открываться —
     * иначе пользователь не сможет перезаписать ключи и починить ситуацию.
     */
    private String safeDecrypt(String stored) {
        try {
            return cipher.decrypt(stored);
        } catch (Exception ex) {
            return null;
        }
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        String k = apiKey.trim();
        if (k.length() <= 6) return "***";
        return k.substring(0, 3) + "..." + k.substring(k.length() - 3);
    }
}
