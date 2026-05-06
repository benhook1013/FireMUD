package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteCommandCoordinator;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteCommandCoordinatorRepository
    extends JpaRepository<RemoteCommandCoordinator, Long> {
  Optional<RemoteCommandCoordinator> findByTenantIdAndCommandId(Long tenantId, String commandId);

  Optional<RemoteCommandCoordinator> findByTenantIdAndCoordinatorId(
      Long tenantId, String coordinatorId);

  Optional<RemoteCommandCoordinator> findByTenantIdAndFollowupId(Long tenantId, String followupId);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId, String state);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId);

  @Query(
      """
      select coordinator from RemoteCommandCoordinator coordinator
      where coordinator.tenantId = :tenantId
        and (:originGameInstanceId is null or coordinator.originGameInstanceId = :originGameInstanceId)
        and (:originRegionId = '' or coordinator.originRegionId = :originRegionId)
        and (:originRegionEpoch = 0 or coordinator.originRegionEpoch = :originRegionEpoch)
        and (:targetGameInstanceId is null or coordinator.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionId = '' or coordinator.targetRegionId = :targetRegionId)
        and (:targetRegionEpoch = 0 or coordinator.targetRegionEpoch = :targetRegionEpoch)
        and (:state = '' or coordinator.state = :state)
        and (:followupId = '' or coordinator.followupId = :followupId)
        and (:scriptId = '' or coordinator.scriptId = :scriptId)
        and (:pluginId = '' or coordinator.pluginId = :pluginId)
        and (:automationDispatchId = '' or coordinator.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or coordinator.commandId = :commandId)
      order by coordinator.updatedAt desc, coordinator.id desc
      """)
  List<RemoteCommandCoordinator> findForControlPlane(
      @Param("tenantId") Long tenantId,
      @Param("originGameInstanceId") Long originGameInstanceId,
      @Param("originRegionId") String originRegionId,
      @Param("originRegionEpoch") long originRegionEpoch,
      @Param("targetGameInstanceId") Long targetGameInstanceId,
      @Param("targetRegionId") String targetRegionId,
      @Param("targetRegionEpoch") long targetRegionEpoch,
      @Param("state") String state,
      @Param("followupId") String followupId,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      Pageable pageable);
}
