package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.*;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.enums.RuntimeState;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.repository.ExchangeConnectionRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExchangeConnectionService {

    private final ExchangeConnectionRepository repo;

    public Page<ExchangeConnectionListItemDto> list(Pageable pageable) {
        return repo.findAll(pageable)
                .map(this::toListItem);
    }

    public ExchangeConnectionDetailDto get(UUID id) {
        ExchangeConnectionEntity e = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + id));
        return toDetail(e);
    }

    public List<ExchangeType> getExchangeTypes() {
        return List.of(ExchangeType.values());
    }

    @Transactional
    public UUID create(ExchangeConnectionCreateRequest req) {
        ExchangeConnectionEntity e = ExchangeConnectionEntity.builder()
                .exchange(req.exchange())
                .name(req.name().trim())
                .apiKey(null)
                .apiSecret(null)
                .passphrase(null)
                .sandboxEnabled(false)
                .active(false)
                .build();

        repo.save(e);
        return e.getId();
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) {
            throw new NotFoundException("Exchange connection not found: " + id);
        }
        repo.deleteById(id);
    }

    @Transactional
    public void updateName(ExchangeUpdateNameDto updateNameDto) {
        ExchangeConnectionEntity e = repo.findById(updateNameDto.id())
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + updateNameDto.id()));
        if (updateNameDto.name() == null || updateNameDto.name().isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        e.setName(updateNameDto.name().trim());
        repo.save(e);
    }

    @Transactional
    public void setSandboxEnabled(ExchangeSetSandboxEnabledDto sandboxEnabledDto) {
        ExchangeConnectionEntity e = repo.findById(sandboxEnabledDto.id())
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + sandboxEnabledDto.id()));
        e.setSandboxEnabled(sandboxEnabledDto.enabled());
        repo.save(e);
    }

    @Transactional
    public void updateCredentials(ExchangeUpdateCredentialsDto updateCredentialsDto) {
        ExchangeConnectionEntity e = repo.findById(updateCredentialsDto.id())
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + updateCredentialsDto.id()));

        e.setApiKey(normalizeNullable(updateCredentialsDto.apiKey()));
        e.setApiSecret(normalizeNullable(updateCredentialsDto.apiSecret()));
        e.setPassphrase(normalizeNullable(updateCredentialsDto.passphrase()));

        repo.save(e);
    }

    private String normalizeNullable(String v) {
        if (v == null) return null;
        String t = v.trim();
        return t.isBlank() ? null : t;
    }

    private ExchangeConnectionListItemDto toListItem(ExchangeConnectionEntity e) {
        return new ExchangeConnectionListItemDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive(),
                RuntimeState.INACTIVE,
                ""
        );
    }

    private ExchangeConnectionDetailDto toDetail(ExchangeConnectionEntity e) {
        String masked = maskKey(e.getApiKey());
        return new ExchangeConnectionDetailDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive(),
                e.isSandboxEnabled(),
                masked,
                e.getApiSecret() != null && !e.getApiSecret().isBlank(),
                e.getPassphrase() != null && !e.getPassphrase().isBlank(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        String k = apiKey.trim();
        if (k.length() <= 6) return "***";
        return k.substring(0, 3) + "..." + k.substring(k.length() - 3);
    }
}
