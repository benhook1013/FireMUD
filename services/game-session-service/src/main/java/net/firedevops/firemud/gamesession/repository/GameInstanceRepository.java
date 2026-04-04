package net.firedevops.firemud.gamesession.repository;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameInstanceRepository extends JpaRepository<GameInstance, Long> {
  Optional<GameInstance> findFirstByTenantIdAndOwnerAccountIdAndStatus(
      Long tenantId, Long ownerAccountId, String status);

  java.util.List<GameInstance> findByStatus(String status);
}
