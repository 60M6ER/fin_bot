package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.larionov.backend.dto.ExchangeConnectionCreateRequest;
import ru.larionov.backend.dto.ExchangeConnectionDetailDto;
import ru.larionov.backend.dto.ExchangeConnectionListItemDto;
import ru.larionov.backend.dto.ExchangeUpdateNameDto;
import ru.larionov.backend.dto.ExchangeSetSandboxEnabledDto;
import ru.larionov.backend.dto.ExchangeUpdateCredentialsDto;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.model.RuntimeInfo;
import ru.larionov.backend.service.ExchangeConnectionService;
import ru.larionov.backend.service.ExchangeRuntimeService;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/exchange-connections")
public class ExchangeConnectionController {

    private final ExchangeConnectionService service;
    private final ExchangeRuntimeService runtimeService;

    @GetMapping
    public Page<ExchangeConnectionListItemDto> list(Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/{id}")
    public ExchangeConnectionDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/types")
    public List<ExchangeType> getExchangeTypes() {
        return service.getExchangeTypes();
    }

    @PostMapping
    public ResponseEntity<UUID> create(@RequestBody ExchangeConnectionCreateRequest req) {
        UUID id = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(id);
    }

    @PatchMapping("/{id}/name")
    public ResponseEntity<Void> updateName(@PathVariable UUID id,
                                           @RequestBody ExchangeUpdateNameDto dto) {
        if (!id.equals(dto.id())) {
            throw new IllegalArgumentException("Path id and body id mismatch");
        }
        service.updateName(dto);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/sandbox")
    public ResponseEntity<Void> setSandbox(@PathVariable UUID id,
                                           @RequestBody ExchangeSetSandboxEnabledDto dto) {
        if (!id.equals(dto.id())) {
            throw new IllegalArgumentException("Path id and body id mismatch");
        }
        service.setSandboxEnabled(dto);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/credentials")
    public ResponseEntity<Void> updateCredentials(@PathVariable UUID id,
                                                  @RequestBody ExchangeUpdateCredentialsDto dto) {
        if (!id.equals(dto.id())) {
            throw new IllegalArgumentException("Path id and body id mismatch");
        }
        service.updateCredentials(dto);
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

    @GetMapping("/{id}/runtime")
    public RuntimeInfo runtime(@PathVariable UUID id) {
        return runtimeService.getRuntime(id);
    }

    @GetMapping("/runtime")
    public List<RuntimeInfo> runtimeAll() {
        return runtimeService.listRuntime();
    }
}
