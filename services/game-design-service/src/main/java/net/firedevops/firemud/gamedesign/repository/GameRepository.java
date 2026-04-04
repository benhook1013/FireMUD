package net.firedevops.firemud.gamedesign.repository;

import jakarta.persistence.LockModeType;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
  Game findByTenantId(String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Game findByTenantIdForUpdate(String tenantId);
}
