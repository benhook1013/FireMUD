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

  List<RemoteCommandCoordinator> findByTenantIdAndFollowupIdIn(
      Long tenantId, java.util.Collection<String> followupIds);

  List<RemoteCommandCoordinator> findByTenantIdAndCoordinatorIdIn(
      Long tenantId, java.util.Collection<String> coordinatorIds);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdAndStateOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId, String state);

  List<RemoteCommandCoordinator> findByTenantIdAndOriginRegionIdOrderByUpdatedAtDesc(
      Long tenantId, String originRegionId);

  @Query(
      """
      select coordinator from RemoteCommandCoordinator coordinator
      left join RemoteFollowup linkedFollowup
        on linkedFollowup.tenantId = coordinator.tenantId
       and linkedFollowup.followupId = coordinator.followupId
      left join GameplayCommand targetCommand
        on targetCommand.tenantId = coordinator.tenantId
       and targetCommand.remoteFollowupId = coordinator.followupId
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
        and (:scriptPatchVersion = '' or coordinator.scriptPatchVersion = :scriptPatchVersion)
        and (:pluginVersionId = '' or coordinator.pluginVersionId = :pluginVersionId)
        and (:playableStateScope = '' or coordinator.playableStateScope = :playableStateScope)
        and (:worldSlug = '' or coordinator.worldSlug = :worldSlug)
        and (:realmSlug = '' or coordinator.realmSlug = :realmSlug)
        and (:pointerVersion is null or coordinator.pointerVersion = :pointerVersion)
        and (:targetEntityId = '' or linkedFollowup.targetEntityId = :targetEntityId)
        and (:effectKey = '' or linkedFollowup.effectKey = :effectKey)
        and (:payloadKind = '' or linkedFollowup.payloadKind = :payloadKind)
        and (:originSourceKind = '' or linkedFollowup.originSourceKind = :originSourceKind)
        and (:automationWorkItemId = '' or coordinator.automationWorkItemId = :automationWorkItemId)
        and (:eventType = '' or linkedFollowup.eventType = :eventType)
        and (:scriptEventId = '' or linkedFollowup.scriptEventId = :scriptEventId)
        and (:automationDispatchId = '' or coordinator.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or coordinator.commandId = :commandId)
        and (:targetCommandId = '' or targetCommand.commandId = :targetCommandId)
        and (:targetCommandExecutionOutcome = ''
             or targetCommand.executionOutcome = :targetCommandExecutionOutcome)
        and (:targetCommandGameplayResult = ''
             or targetCommand.gameplayResult = :targetCommandGameplayResult)
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
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("pluginVersionId") String pluginVersionId,
      @Param("playableStateScope") String playableStateScope,
      @Param("worldSlug") String worldSlug,
      @Param("realmSlug") String realmSlug,
      @Param("pointerVersion") Long pointerVersion,
      @Param("targetEntityId") String targetEntityId,
      @Param("effectKey") String effectKey,
      @Param("payloadKind") String payloadKind,
      @Param("originSourceKind") String originSourceKind,
      @Param("automationWorkItemId") String automationWorkItemId,
      @Param("eventType") String eventType,
      @Param("scriptEventId") String scriptEventId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      @Param("targetCommandId") String targetCommandId,
      @Param("targetCommandExecutionOutcome") String targetCommandExecutionOutcome,
      @Param("targetCommandGameplayResult") String targetCommandGameplayResult,
      Pageable pageable);
}
