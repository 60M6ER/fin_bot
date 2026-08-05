package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.larionov.backend.dto.SystemInfoDto;
import ru.larionov.backend.service.SystemRestartService;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemRestartService service;

    /**
     * Живёт под /api намеренно: dev-сервер Quasar проксирует только /api,
     * поэтому /actuator/health фронтенду недоступен.
     */
    @GetMapping("/info")
    public SystemInfoDto info() {
        return service.info();
    }

    /** 202: выключение начнётся после того, как ответ уйдёт клиенту. */
    @PostMapping("/restart")
    public ResponseEntity<SystemInfoDto> restart() {
        return ResponseEntity.accepted().body(service.requestRestart());
    }
}
