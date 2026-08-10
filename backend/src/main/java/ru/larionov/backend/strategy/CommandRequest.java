package ru.larionov.backend.strategy;

import java.math.BigDecimal;

/**
 * Команда оператора вместе с её параметром.
 *
 * Отдельный тип, а не голое перечисление, потому что у команд появились параметры:
 * «изменить бюджет» без суммы — не команда, а недоразумение. Проверка обязательности
 * живёт здесь, в конструкторе, чтобы недоделанная команда не доехала до потока бота
 * и не превратилась там в исключение посреди торгового цикла.
 *
 * @param command что делать
 * @param amount  сумма, если команда её требует; иначе null
 */
public record CommandRequest(StrategyCommand command, BigDecimal amount) {

    public CommandRequest {
        if (command == null) {
            throw new IllegalArgumentException("Команда не указана");
        }
        if (command.requiresAmount() && (amount == null || amount.signum() <= 0)) {
            throw new IllegalArgumentException(
                    "Команде «%s» нужна положительная сумма".formatted(command.title()));
        }
    }

    /** Команда без параметра. */
    public static CommandRequest of(StrategyCommand command) {
        return new CommandRequest(command, null);
    }

    /** Сумма команды, которая её требует. Конструктор уже гарантировал, что она есть. */
    public BigDecimal requireAmount() {
        if (amount == null) {
            throw new IllegalStateException(
                    "У команды «%s» нет суммы".formatted(command.title()));
        }
        return amount;
    }
}
