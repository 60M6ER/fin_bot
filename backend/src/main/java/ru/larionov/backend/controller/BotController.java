package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.larionov.backend.dto.BotCreateRequest;
import ru.larionov.backend.dto.BotDetailDto;
import ru.larionov.backend.dto.BotEventDto;
import ru.larionov.backend.dto.BotListItemDto;
import ru.larionov.backend.dto.BotTradingStateDto;
import ru.larionov.backend.dto.BotUpdateRequest;
import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.service.BotService;
import ru.larionov.backend.service.BotRuntimeService;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bots")
public class BotController {

    private final BotService service;
    private final BotRuntimeService runtimeService;

    @GetMapping
    public List<BotListItemDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BotDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/strategy-types")
    public List<StrategyType> strategyTypes() {
        return service.getStrategyTypes();
    }

    @PostMapping
    public ResponseEntity<UUID> create(@RequestBody BotCreateRequest req) {
        UUID id = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody BotUpdateRequest req) {
        service.update(id, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        runtimeService.activate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        runtimeService.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    /** Что бот делает прямо сейчас: позиция, активные заявки, очередь событий. */
    @GetMapping("/{id}/state")
    public BotTradingStateDto state(@PathVariable UUID id) {
        return service.tradingState(id);
    }

    @GetMapping("/{id}/events")
    public List<BotEventDto> events(@PathVariable UUID id,
                                    @RequestParam(defaultValue = "100") int limit) {
        return service.events(id, limit);
    }

    @GetMapping("/{id}/runtime")
    public RuntimeInfo runtime(@PathVariable UUID id) {
        return runtimeService.getRuntime(id);
    }

    @GetMapping("/runtime")
    public List<RuntimeInfo> runtimeAll() {
        return runtimeService.getRuntimeAll().values().stream().toList();
    }
}
