package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.ExchangeConnectionEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExchangeConnectionRepository extends JpaRepository<ExchangeConnectionEntity, UUID> {
    List<ExchangeConnectionEntity> findAllByActiveTrueOrderByNameAsc();
}
