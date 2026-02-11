package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.BotCreateRequest;
import ru.larionov.backend.dto.BotDetailDto;
import ru.larionov.backend.dto.BotListItemDto;
import ru.larionov.backend.entity.BotEntity;
import ru.larionov.backend.entity.ExchangeConnectionEntity;
import ru.larionov.backend.enums.BotStatus;
import ru.larionov.backend.exception.NotFoundException;
import ru.larionov.backend.repository.BotRepository;
import ru.larionov.backend.repository.ExchangeConnectionRepository;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BotService {

    private final BotRepository botRepo;
    private final ExchangeConnectionRepository connRepo;

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
        ExchangeConnectionEntity conn = connRepo.findById(req.exchangeConnectionId())
                .orElseThrow(() -> new NotFoundException("Exchange connection not found: " + req.exchangeConnectionId()));

        BotEntity b = BotEntity.builder()
                .name(req.name().trim())
                .exchangeConnection(conn)
                .strategyType(req.strategyType())
                .strategyConfig(normalizeJson(req.strategyConfig()))
                .symbol(req.symbol().trim())
                .timeframe(req.timeframe() == null ? null : req.timeframe().trim())
                .status(req.status() == null ? BotStatus.DRAFT : req.status())
                .enabled(req.enabled() == null || req.enabled())
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
        ExchangeConnectionEntity c = b.getExchangeConnection();
        return new BotListItemDto(
                b.getId(),
                b.getName(),
                b.getStrategyType(),
                b.getSymbol(),
                b.getStatus(),
                b.isEnabled(),
                c.getId(),
                c.getName()
        );
    }

    private BotDetailDto toDetail(BotEntity b) {
        ExchangeConnectionEntity c = b.getExchangeConnection();
        return new BotDetailDto(
                b.getId(),
                b.getName(),
                c.getId(),
                c.getName(),
                b.getStrategyType(),
                b.getStrategyConfig(),
                b.getSymbol(),
                b.getTimeframe(),
                b.getStatus(),
                b.isEnabled(),
                b.getCreatedAt(),
                b.getUpdatedAt()
        );
    }

    private String normalizeJson(String json) {
        if (json == null || json.isBlank()) return "{}";
        return json.trim();
    }
}
