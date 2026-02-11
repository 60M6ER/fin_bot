package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import ru.larionov.backend.dto.ExchangeConnectionCreateRequest;
import ru.larionov.backend.dto.ExchangeConnectionDetailDto;
import ru.larionov.backend.dto.ExchangeConnectionListItemDto;
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
    public List<ExchangeConnectionListItemDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ExchangeConnectionDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody ExchangeConnectionCreateRequest req,
                                       UriComponentsBuilder ucb) {
        UUID id = service.create(req);
        URI location = ucb.path("/api/v1/exchange-connections/{id}").build(id);
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
