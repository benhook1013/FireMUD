package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import net.firedevops.firemud.gamesession.entity.TickEffect;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TickEffectRepository extends JpaRepository<TickEffect, Long> {
  List<TickEffect> findByTickBatchId(String tickBatchId);

  List<TickEffect> findByTickBatchIdAndStatusOrderByIdAsc(String tickBatchId, String status);
}
