package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.larionov.backend.dto.AppSettingsDto;
import ru.larionov.backend.dto.UpdateDisplayCurrencyDto;
import ru.larionov.backend.dto.UpdateTelegramSettingsDto;
import ru.larionov.backend.dto.UpdateTradingEnabledDto;
import ru.larionov.backend.service.AppSettingsService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private final AppSettingsService service;

    @GetMapping
    public AppSettingsDto get() {
        return service.get();
    }

    @PutMapping("/telegram")
    public ResponseEntity<Void> updateTelegram(@RequestBody UpdateTelegramSettingsDto dto) {
        service.updateTelegram(dto);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/display-currency")
    public ResponseEntity<Void> updateDisplayCurrency(@RequestBody UpdateDisplayCurrencyDto dto) {
        service.updateDisplayCurrency(dto);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/trading")
    public ResponseEntity<Void> setTradingEnabled(@RequestBody UpdateTradingEnabledDto dto) {
        service.setTradingEnabled(dto.enabled());
        return ResponseEntity.noContent().build();
    }
}
