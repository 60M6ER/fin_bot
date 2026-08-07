package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.larionov.backend.entity.GridGenerationEntity;

import java.util.List;
import java.util.UUID;

public interface GridGenerationRepository extends JpaRepository<GridGenerationEntity, UUID> {

    List<GridGenerationEntity> findAllByBotIdAndDryRunOrderByGenerationAsc(UUID botId, boolean dryRun);

    void deleteAllByBotId(UUID botId);
}
