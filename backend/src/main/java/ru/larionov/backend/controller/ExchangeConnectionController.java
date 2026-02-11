package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import ru.larionov.backend.dto.ExchangeConnectionCreateRequest;
import ru.larionov.backend.dto.ExchangeConnectionDetailDto;
import ru.larionov.backend.dto.ExchangeConnectionListItemDto;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.service.ExchangeConnectionService;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/exchange-connections")
public class ExchangeConnectionController {

    private final ExchangeConnectionService service;

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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
