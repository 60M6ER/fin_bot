package ru.larionov.backend.dto;

import java.time.Instant;

/**
 * Кто сейчас отвечает и с какого момента.
 *
 * {@code instanceId} — ручка для фронтенда: он опрашивает этот эндпоинт после
 * перезапуска и ждёт именно СМЕНЫ идентификатора. Проверки «кто-нибудь отвечает?»
 * недостаточно — при graceful shutdown старый процесс продолжает отвечать 200
 * ещё до 30 секунд, и опрос объявил бы успех против умирающего JVM.
 */
public record SystemInfoDto(
        String instanceId,
        Instant startedAt,
        boolean restartEnabled,
        boolean restarting
) {
}
