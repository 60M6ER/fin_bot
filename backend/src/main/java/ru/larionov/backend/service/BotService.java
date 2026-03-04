package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.BotCreateRequest;
import ru.larionov.backend.dto.BotDetailDto;
import ru.larionov.backend.dto.BotListItemDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.enums.RuntimeState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BotService {

    private final BotRepository botRepo;
    private final BotRuntimeService botRuntimeService;

    public List<BotListItemDto> list() {
        return botRepo.findAll().stream()
                .map(this::toListItem)
                .toList();
    }

    public BotDetailDto get(UUID id) {
        BotEntity b = botRepo.findById(id)
                .orElseThrow(() -> new NotFoundException("Bot not found: " + id));
        return toDetail(b);
    }

    @Transactional
    public UUID create(BotCreateRequest req) {
        BotEntity b = BotEntity.builder()
                .name(req.name().trim())
                .active(false)
                .strategyType(null)
                .strategyConfig("{}")
                .build();

        botRepo.save(b);
        return b.getId();
    }

    @Transactional
    public void delete(UUID id) {
        if (!botRepo.existsById(id)) {
            throw new NotFoundException("Bot not found: " + id);
        }
        botRepo.deleteById(id);
    }

    private BotListItemDto toListItem(BotEntity b) {
        return new BotListItemDto(
                b.getId(),
                b.getName(),
                b.getStrategyType(),
                b.isActive(),
                runtimeOrDefault(b.getId())
        );
    }

    private BotDetailDto toDetail(BotEntity b) {
        return new BotDetailDto(
                b.getId(),
                b.getName(),
                b.getStrategyType(),
                b.getStrategyConfig(),
                b.isActive(),
                runtimeOrDefault(b.getId()),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private RuntimeInfo runtimeOrDefault(UUID id) {
        RuntimeInfo info = botRuntimeService.getRuntime(id);
        if (info != null) return info;
        return new RuntimeInfo(id, RuntimeState.INACTIVE, null, Instant.now());
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        return json.trim();
    }
}
