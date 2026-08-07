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
import ru.larionov.backend.dto.BotValuationDto;
import ru.larionov.backend.dto.MoneyLedgerDto;
import ru.larionov.backend.dto.GridGenerationDto;
import ru.larionov.backend.dto.GridPreviewDto;
import ru.larionov.backend.dto.GridPreviewRequest;
import ru.larionov.backend.enums.StrategyType;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.service.BotService;
import ru.larionov.backend.service.BotRuntimeService;
import ru.larionov.backend.service.GridPreviewService;
import ru.larionov.backend.strategy.StrategyCommand;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bots")
public class BotController {

    private final BotService service;
    private final BotRuntimeService runtimeService;
    private final GridPreviewService gridPreviewService;

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

    @PostMapping("/grid-preview")
    public GridPreviewDto gridPreview(@RequestBody GridPreviewRequest request) {
        return gridPreviewService.preview(request);
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

    /**
     * Закрыть позицию по рынку, зафиксировать убыток и построить сетку заново.
     *
     * Ручное решение оператора: снимает потолок убытка и лимит перестановок,
     * которые в этот момент и держат бота в тупике. Выполняется асинхронно на
     * потоке бота — результат придёт событиями и в Telegram.
     */
    @PostMapping("/{id}/force-grid-replacement")
    public ResponseEntity<Void> forceGridReplacement(@PathVariable UUID id) {
        runtimeService.command(id, StrategyCommand.FORCE_GRID_REPLACEMENT);
        return ResponseEntity.accepted().build();
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

    @GetMapping("/{id}/accounting")
    public BotValuationDto accounting(@PathVariable UUID id,
                                      @RequestParam(required = false) Boolean dryRun) {
        return service.accounting(id, dryRun);
    }

    @GetMapping("/{id}/ledger")
    public List<MoneyLedgerDto> ledger(@PathVariable UUID id,
                                       @RequestParam(required = false) Boolean dryRun) {
        return service.ledger(id, dryRun);
    }

    /** Итоги по поколениям сетки: во сколько обошёлся вход в диапазон и что он принёс. */
    @GetMapping("/{id}/generations")
    public List<GridGenerationDto> generations(@PathVariable UUID id,
                                               @RequestParam(required = false) Boolean dryRun) {
        return service.generations(id, dryRun);
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
