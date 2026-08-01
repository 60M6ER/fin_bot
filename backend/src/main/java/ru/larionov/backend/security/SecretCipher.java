package ru.larionov.backend.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Шифрование секретов, лежащих в БД (ключи брокера, токен Telegram).
 *
 * Что это даёт и чего не даёт: защищает дамп/бэкап базы от того, чтобы ключи брокера
 * читались глазами. От того, у кого есть доступ к машине и переменным окружения, не защищает —
 * ключ шифрования лежит там же.
 *
 * Ключ берётся из APP_SECRET_KEY. Если переменная не задана, значения сохраняются
 * открытым текстом с предупреждением при старте: молча притворяться, что данные
 * зашифрованы, хуже, чем честно сказать, что нет. Префикс {@value #PREFIX} у значения
 * показывает, зашифровано оно или нет, поэтому включать шифрование можно позже —
 * старые открытые значения продолжат читаться.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String PREFIX = "enc:v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${app.secret-key:}") String rawKey) {
        this.key = buildKey(rawKey);
    }

    @PostConstruct
    void warnIfDisabled() {
        if (key == null) {
            log.warn("APP_SECRET_KEY не задан — ключи брокера и токен Telegram хранятся в БД "
                    + "ОТКРЫТЫМ ТЕКСТОМ. Задайте APP_SECRET_KEY, чтобы включить шифрование.");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось зашифровать значение: " + e.getMessage(), e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            // Значение сохранено до включения шифрования — отдаём как есть.
            return stored;
        }
        if (key == null) {
            // Тихо вернуть шифротекст было бы худшим вариантом: бот попытался бы
            // авторизоваться мусорным токеном и упал бы с невнятной ошибкой брокера.
            throw new IllegalStateException(
                    "В БД есть зашифрованные значения, но APP_SECRET_KEY не задан. "
                            + "Задайте тот же ключ, которым они были зашифрованы.");
        }

        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));

            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);

            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось расшифровать значение — вероятно, APP_SECRET_KEY отличается от того, "
                            + "которым оно было зашифровано.", e);
        }
    }

    /**
     * Принимаем и base64 32-байтного ключа, и произвольную парольную фразу:
     * фразу приводим к 256 битам через SHA-256, чтобы не заставлять руками генерировать ключ.
     */
    private static SecretKey buildKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String trimmed = rawKey.trim();

        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length == 32) {
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // не base64 — считаем парольной фразой
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(trimmed.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось построить ключ шифрования: " + e.getMessage(), e);
        }
    }
}
