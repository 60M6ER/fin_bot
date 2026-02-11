package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.BotEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotRepository extends JpaRepository<BotEntity, UUID> {
    List<BotEntity> findAllByEnabledTrueOrderByNameAsc();
}
