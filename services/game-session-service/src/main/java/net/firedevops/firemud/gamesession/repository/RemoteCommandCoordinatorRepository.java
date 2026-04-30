package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RemoteCommandCoordinatorRepository
    extends JpaRepository<RemoteCommandCoordinator, Long> {
  Optional<RemoteCommandCoordinator> findByTenantIdAndCoordinatorId(
      Long tenantId, String coordinatorId);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId, String state);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId);
}
