package net.firedevops.firemud.gamedesign.repository;

import jakarta.persistence.LockModeType;
import net.firedevops.firemud.gamedesign.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
  Game findByTenantId(String tenantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select g from Game g where g.tenantId = :tenantId")
  Game findByTenantIdForUpdate(@Param("tenantId") String tenantId);
}
