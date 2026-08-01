package ru.larionov.backend.exchange.api.model;

import ru.larionov.backend.enums.ExchangeType;

import java.util.UUID;

/**
 * Всё, что нужно адаптеру биржи для работы, — уже разрешённое:
 * секреты расшифрованы, настройки разобраны из jsonb.
 *
 * Адаптеры не должны знать про JPA-сущности и шифрование: их дело — говорить с биржей.
 */
public record ExchangeConnectionContext(
        UUID id,
        ExchangeType exchange,
        String name,
        String apiKey,
        String apiSecret,
        String passphrase,
        boolean sandboxEnabled,
        String accountId,
        ExchangeConnectionSettings settings
) {
    public boolean hasAccountId() {
        return accountId != null && !accountId.isBlank();
    }
}
