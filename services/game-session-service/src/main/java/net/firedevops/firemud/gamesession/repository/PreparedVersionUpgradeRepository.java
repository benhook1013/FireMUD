package net.firedevops.firemud.gamesession.repository;

import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.PreparedVersionUpgrade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PreparedVersionUpgradeRepository
    extends JpaRepository<PreparedVersionUpgrade, Long> {
  Optional<PreparedVersionUpgrade> findByPreparationId(String preparationId);

  Optional<PreparedVersionUpgrade> findByTenantIdAndControlPlaneRequestId(
      Long tenantId, String controlPlaneRequestId);
}
