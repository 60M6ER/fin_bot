package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.larionov.backend.entity.BotStrategyStateEntity;

import java.util.UUID;

public interface BotStrategyStateRepository extends JpaRepository<BotStrategyStateEntity, UUID> {
}
