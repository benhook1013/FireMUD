package net.firedevops.firemud.gamedesign.repository;

import net.firedevops.firemud.gamedesign.entity.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {
  Game findByTenantId(String tenantId);
}
