package ru.larionov.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.larionov.backend.entity.BotEntity;

import java.util.List;
import java.util.UUID;

@Repository
public interface BotRepository extends JpaRepository<BotEntity, UUID> {

    /** Все боты с desired-state = active (используется супервизором). */
    List<BotEntity> findAllByActiveTrueOrderByNameAsc();

    /** Боты конкретного подключения с desired-state = active — каскад запуска. */
    List<BotEntity> findAllByExchangeConnectionIdAndActiveTrueOrderByNameAsc(UUID exchangeConnectionId);

    /** Все боты подключения независимо от desired-state — каскад остановки и UI. */
    List<BotEntity> findAllByExchangeConnectionIdOrderByNameAsc(UUID exchangeConnectionId);

    /** Есть ли у подключения боты, которые пользователь хочет держать запущенными. */
    boolean existsByExchangeConnectionIdAndActiveTrue(UUID exchangeConnectionId);

    boolean existsByExchangeConnectionId(UUID exchangeConnectionId);
}
