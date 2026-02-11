package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import ru.larionov.backend.dto.BotCreateRequest;
import ru.larionov.backend.dto.BotDetailDto;
import ru.larionov.backend.dto.BotListItemDto;
import ru.larionov.backend.service.BotService;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/bots")
public class BotController {

    private final BotService service;

    @GetMapping
    public List<BotListItemDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public BotDetailDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<Void> create(@RequestBody BotCreateRequest req,
                                       UriComponentsBuilder ucb) {
        UUID id = service.create(req);
        URI location = ucb.path("/api/v1/bots/{id}").build(id);
        return ResponseEntity.created(location).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
