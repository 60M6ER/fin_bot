package ru.larionov.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.larionov.backend.dto.InstrumentDto;
import ru.larionov.backend.enums.ExchangeType;
import ru.larionov.backend.exchange.api.enums.InstrumentKind;
import ru.larionov.backend.exchange.api.enums.MarketSegment;
import ru.larionov.backend.model.InstrumentSyncStatus;
import ru.larionov.backend.service.InstrumentCatalogService;
import ru.larionov.backend.service.InstrumentSyncService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

    private final InstrumentCatalogService catalog;
    private final InstrumentSyncService sync;

    /**
     * Поиск для автокомплита: по нашему справочнику, поэтому быстрый и не требует
     * активного подключения к брокеру.
     *
     * Пагинации нет намеренно — за двадцатую позицию в автокомплите никто не листает,
     * дописывают символы. Отсюда только limit.
     */
    @GetMapping
    public List<InstrumentDto> search(
            @RequestParam ExchangeType exchange,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) InstrumentKind kind,
            @RequestParam(required = false) MarketSegment segment,
            @RequestParam(defaultValue = "true") boolean onlyTradable,
            @RequestParam(required = false) Integer limit) {
        return catalog.search(exchange, query, kind, segment, onlyTradable, limit);
    }

    /**
     * Разрешение uid в подпись. Нужен форме бота: в конфиге хранится только uid,
     * а выпадающему списку надо показать «SBER · Сбербанк · Акция · MOEX».
     */
    @GetMapping("/{uid}")
    public InstrumentDto get(@PathVariable String uid,
                             @RequestParam(required = false) ExchangeType exchange) {
        return catalog.getByUid(uid, exchange);
    }

    /** Ручное обновление справочника. 202: работа уходит в фон, статус смотреть в /sync-status. */
    @PostMapping("/sync")
    public ResponseEntity<InstrumentSyncStatus> sync(@RequestParam ExchangeType exchange) {
        return ResponseEntity.accepted().body(sync.requestSync(exchange));
    }

    @GetMapping("/sync-status")
    public List<InstrumentSyncStatus> syncStatus() {
        return sync.statuses();
    }
}
