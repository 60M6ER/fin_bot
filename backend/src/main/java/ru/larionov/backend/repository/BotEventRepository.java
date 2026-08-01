package ru.larionov.backend.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.BotEventEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotEventRepository extends JpaRepository<BotEventEntity, UUID> {

    List<BotEventEntity> findAllByBotIdOrderByTsDesc(UUID botId, Pageable pageable);

    void deleteAllByBotId(UUID botId);
}
