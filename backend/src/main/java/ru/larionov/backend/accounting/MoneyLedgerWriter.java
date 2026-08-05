package ru.larionov.backend.accounting;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.larionov.backend.entity.MoneyLedgerEntity;
import ru.larionov.backend.repository.MoneyLedgerRepository;

@Service
@RequiredArgsConstructor
public class MoneyLedgerWriter {

    private final MoneyLedgerRepository ledgerRepo;

    /**
     * Изолирует конфликт уникального ключа от транзакции, обрабатывающей состояние ордера.
     * После неудачного flush Hibernate-сессию использовать повторно нельзя.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(MoneyLedgerEntity entry) {
        ledgerRepo.saveAndFlush(entry);
    }
}
