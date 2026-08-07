package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.FxRateEntity;

@Repository
public interface FxRateRepository extends JpaRepository<FxRateEntity, String> {
}
