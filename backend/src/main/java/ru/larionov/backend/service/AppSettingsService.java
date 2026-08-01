package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.AppSettingsDto;
import ru.larionov.backend.dto.UpdateTelegramSettingsDto;
import ru.larionov.backend.security.SecretCipher;
import ru.larionov.backend.telegram.config.TelegramSettings;

/** Фасад настроек всей программы для REST-слоя. */
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private final AppSettingService settings;
    private final TelegramSettings telegramSettings;
    private final TradingSwitch tradingSwitch;
    private final SecretCipher cipher;

    public AppSettingsDto get() {
        return new AppSettingsDto(
                telegramSettings.usable(),
                telegramSettings.maskedToken(),
                telegramSettings.username(),
                telegramSettings.isRegistered(),
                tradingSwitch.isEnabled(),
                cipher.isEnabled()
        );
    }

    @Transactional
    public void updateTelegram(UpdateTelegramSettingsDto dto) {
        if (dto.clearToken()) {
            settings.delete(AppSettingKeys.TELEGRAM_BOT_TOKEN);
        } else if (dto.token() != null && !dto.token().isBlank()) {
            String token = dto.token().trim();
            if (!TelegramSettings.looksLikeRealToken(token)) {
                throw new IllegalArgumentException(
                        "Токен не похож на токен Telegram — ожидается вид <id>:<секрет>.");
            }
            settings.set(AppSettingKeys.TELEGRAM_BOT_TOKEN, token, true);
        }
        // Пустой token без clearToken означает «не менять»: так UI может сохранить
        // одно только имя бота, не заставляя вводить токен заново.

        if (dto.username() != null) {
            settings.set(AppSettingKeys.TELEGRAM_BOT_USERNAME, dto.username().trim(), false);
        }
    }

    @Transactional
    public void setTradingEnabled(boolean enabled) {
        tradingSwitch.set(enabled);
    }
}
