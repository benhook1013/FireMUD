package net.firedevops.firemud.gamesession.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameInstanceRepository extends JpaRepository<GameInstance, Long> {
  Optional<GameInstance> findFirstByTenantIdAndOwnerAccountIdAndStatus(
      Long tenantId, Long ownerAccountId, String status);

  List<GameInstance> findByStatus(String status);

  List<GameInstance> findByTenantIdAndOwnerAccountIdInAndStatus(
      Long tenantId, Collection<Long> ownerAccountIds, String status);
}
