package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.AppSettingEntity;

@Repository
public interface AppSettingRepository extends JpaRepository<AppSettingEntity, String> {
}
