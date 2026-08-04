package ru.larionov.backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;

/**
 * Без этого любая доменная ошибка уходила клиенту как голый 500, и текст пропадал:
 * фронт читает поле message, а тело ошибки Spring по умолчанию его не содержит.
 * В результате пользователь вместо «Нельзя изменить ключи: подключение активно»
 * видел «Ошибка запроса».
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException e) {
        return build(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Некорректные входные данные. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /**
     * Неразбираемое значение query-параметра: ?exchange=NOPE, ?kind=FOO, ?limit=abc.
     * Без этого обработчика Spring отдавал бы голый 500, и фронт показывал бы
     * «Ошибка запроса» вместо внятной причины.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(HttpStatus.BAD_REQUEST,
                "Некорректное значение параметра " + e.getName() + ": " + e.getValue());
    }

    /** Действие не разрешено в текущем состоянии: бот запущен, подключение активно и т.п. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(IllegalStateException e) {
        return build(HttpStatus.CONFLICT, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message == null ? "" : message
        ));
    }
}
