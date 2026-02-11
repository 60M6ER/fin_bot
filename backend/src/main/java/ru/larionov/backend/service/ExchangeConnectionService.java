package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.ExchangeConnectionCreateRequest;
import ru.larionov.backend.dto.ExchangeConnectionDetailDto;
import ru.larionov.backend.dto.ExchangeConnectionListItemDto;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.ExchangeType;
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
                .apiKey(req.apiKey().trim())
                .apiSecret(req.apiSecret().trim())
                .passphrase(req.passphrase() == null ? null : req.passphrase().trim())
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

    private ExchangeConnectionListItemDto toListItem(ExchangeConnectionEntity e) {
        return new ExchangeConnectionListItemDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive()
        );
    }

    private ExchangeConnectionDetailDto toDetail(ExchangeConnectionEntity e) {
        String masked = maskKey(e.getApiKey());
        return new ExchangeConnectionDetailDto(
                e.getId(),
                e.getExchange(),
                e.getName(),
                e.isActive(),
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
