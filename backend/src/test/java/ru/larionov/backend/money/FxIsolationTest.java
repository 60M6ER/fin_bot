package ru.larionov.backend.money;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Структурная защита главного инварианта: КОНВЕРТАЦИЯ ВАЛЮТ УЧАСТВУЕТ ТОЛЬКО В ПОКАЗЕ.
 *
 * Ни размер заявки, ни бюджет бота, ни один риск-лимит не имеют права зависеть от
 * курса. Иначе скачок курса молча передвинул бы объём заявки или сработал бы
 * стоп-лимит без единой сделки — то есть внешний, ненадёжный и запаздывающий
 * источник данных начал бы управлять реальными деньгами.
 *
 * Такое требование невозможно удержать одними намерениями: достаточно одного
 * «удобного» импорта в стратегии, чтобы оно тихо перестало выполняться, и никакой
 * обычный тест этого не заметит. Поэтому проверяем сами исходники.
 */
class FxIsolationTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/ru/larionov/backend");

    /** Пакеты, принимающие торговые решения. */
    private static final List<String> TRADING_PACKAGES = List.of("strategy", "execution");

    @Test
    void tradingCodeDoesNotDependOnCurrencyConversion() throws IOException {
        List<String> violations = new ArrayList<>();

        for (String pkg : TRADING_PACKAGES) {
            Path root = SOURCE_ROOT.resolve(pkg);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                    try {
                        String source = Files.readString(file);
                        if (source.contains("ru.larionov.backend.money")
                                || source.contains("FxRateService")
                                || source.contains("FxRate")) {
                            violations.add(SOURCE_ROOT.relativize(file).toString());
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("Не прочитать " + file, e);
                    }
                });
            }
        }

        assertThat(violations)
                .as("""
                        Торговый код обратился к валютному слою. Бюджет бота и все лимиты \
                        обязаны оставаться в валюте котировки инструмента: пересчёт по курсу \
                        сделал бы объём заявки зависящим от внешнего источника данных.""")
                .isEmpty();
    }

    /** Обратное направление: валютный слой не должен тянуть за собой торговый. */
    @Test
    void currencyLayerDoesNotDependOnTradingInternals() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(SOURCE_ROOT.resolve("money"))) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                try {
                    String source = Files.readString(file);
                    if (source.contains("ru.larionov.backend.strategy")
                            || source.contains("ru.larionov.backend.execution")) {
                        violations.add(SOURCE_ROOT.relativize(file).toString());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Не прочитать " + file, e);
                }
            });
        }

        assertThat(violations)
                .as("Валютный слой знает про торговый — зависимость обязана идти только в одну сторону")
                .isEmpty();
    }
}
