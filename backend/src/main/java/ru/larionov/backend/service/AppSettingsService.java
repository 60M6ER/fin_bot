package ru.larionov.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.dto.AppSettingsDto;
import ru.larionov.backend.dto.UpdateDisplayCurrencyDto;
import ru.larionov.backend.dto.UpdateTelegramSettingsDto;
import ru.larionov.backend.money.CbrFxProvider;
import ru.larionov.backend.money.CurrencyCode;
import ru.larionov.backend.money.FxRate;
import ru.larionov.backend.money.FxRateService;
import ru.larionov.backend.money.TInvestFxProvider;
import ru.larionov.backend.security.SecretCipher;
import ru.larionov.backend.telegram.config.TelegramSettings;

import java.util.Optional;

/** Фасад настроек всей программы для REST-слоя. */
@Service
@RequiredArgsConstructor
public class AppSettingsService {

    private final AppSettingService settings;
    private final TelegramSettings telegramSettings;
    private final TradingSwitch tradingSwitch;
    private final SecretCipher cipher;
    private final FxRateService fx;

    public AppSettingsDto get() {
        Optional<FxRate> usdRub = fx.usdRub();
        return new AppSettingsDto(
                telegramSettings.usable(),
                telegramSettings.maskedToken(),
                telegramSettings.username(),
                telegramSettings.isRegistered(),
                tradingSwitch.isEnabled(),
                cipher.isEnabled(),
                displayCurrency(),
                fx.preferredSource(),
                usdRub.map(FxRate::rate).orElse(null),
                usdRub.map(FxRate::asOf).orElse(null)
        );
    }

    public String displayCurrency() {
        return CurrencyCode.normalize(
                settings.get(AppSettingKeys.DISPLAY_CURRENCY, CurrencyCode.RUB));
    }

    /**
     * Валюта показа и источник курса.
     *
     * Проверка значений здесь не формальность: и то и другое попадает в подпись под
     * деньгами, а неизвестный источник молча превратил бы её в прочерк.
     */
    @Transactional
    public void updateDisplayCurrency(UpdateDisplayCurrencyDto dto) {
        if (dto.displayCurrency() != null) {
            String currency = CurrencyCode.normalize(dto.displayCurrency());
            if (!CurrencyCode.RUB.equals(currency) && !CurrencyCode.USD.equals(currency)) {
                throw new IllegalArgumentException(
                        "Валютой показа может быть только RUB или USD, получено: " + dto.displayCurrency());
            }
            settings.set(AppSettingKeys.DISPLAY_CURRENCY, currency, false);
        }

        if (dto.fxSource() != null) {
            String source = dto.fxSource().trim().toUpperCase(java.util.Locale.ROOT);
            if (!CbrFxProvider.ID.equals(source) && !TInvestFxProvider.ID.equals(source)) {
                throw new IllegalArgumentException(
                        "Источником курса может быть только CBR или T_INVEST, получено: " + dto.fxSource());
            }
            settings.set(AppSettingKeys.FX_SOURCE, source, false);
        }
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
