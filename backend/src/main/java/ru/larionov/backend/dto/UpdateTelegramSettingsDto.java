package ru.larionov.backend.dto;

/**
 * Пустой token означает «не менять» — чтобы UI мог сохранить одно только имя бота,
 * не заставляя пользователя вводить токен заново. Для удаления есть отдельный флаг.
 */
public record UpdateTelegramSettingsDto(
        String token,
        String username,
        boolean clearToken
) {}
