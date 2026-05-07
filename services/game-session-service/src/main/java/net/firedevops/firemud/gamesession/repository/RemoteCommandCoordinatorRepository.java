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
      left join RuntimeRegionStatus currentOrigin
        on currentOrigin.tenantId = coordinator.tenantId
       and currentOrigin.gameInstanceId = coordinator.originGameInstanceId
      left join RuntimeRegionStatus currentTarget
        on currentTarget.tenantId = coordinator.tenantId
       and currentTarget.gameInstanceId = coordinator.targetGameInstanceId
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
        and (:currentOriginRuntimeRegionId = ''
             or currentOrigin.regionId = :currentOriginRuntimeRegionId)
        and (:currentOriginRuntimeRegionEpoch = 0
             or currentOrigin.regionEpoch = :currentOriginRuntimeRegionEpoch)
        and (:currentOriginRuntimeGameInstanceId is null
             or currentOrigin.gameInstanceId = :currentOriginRuntimeGameInstanceId)
        and (:currentTargetRuntimeRegionId = ''
             or currentTarget.regionId = :currentTargetRuntimeRegionId)
        and (:currentTargetRuntimeRegionEpoch = 0
             or currentTarget.regionEpoch = :currentTargetRuntimeRegionEpoch)
        and (:currentTargetRuntimeGameInstanceId is null
             or currentTarget.gameInstanceId = :currentTargetRuntimeGameInstanceId)
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
        and (:claimTargetAggregate = ''
             or linkedFollowup.claimTargetAggregate = :claimTargetAggregate)
        and (:effectKey = '' or linkedFollowup.effectKey = :effectKey)
        and (:payloadKind = '' or linkedFollowup.payloadKind = :payloadKind)
        and (:originSourceKind = '' or linkedFollowup.originSourceKind = :originSourceKind)
        and (:originSourceState = '' or linkedFollowup.originSourceState = :originSourceState)
        and (:automationWorkItemId = '' or coordinator.automationWorkItemId = :automationWorkItemId)
        and (:eventType = '' or linkedFollowup.eventType = :eventType)
        and (:scriptEventId = '' or linkedFollowup.scriptEventId = :scriptEventId)
        and (:automationDispatchId = '' or coordinator.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or coordinator.commandId = :commandId)
        and (:lateResultPolicy = '' or coordinator.lateResultPolicy = :lateResultPolicy)
        and (:executionOutcome = '' or coordinator.executionOutcome = :executionOutcome)
        and (:gameplayResult = '' or coordinator.gameplayResult = :gameplayResult)
        and (:followupStatus = '' or linkedFollowup.status = :followupStatus)
        and (:followupClaimedTickBatchId = ''
             or linkedFollowup.claimedTickBatchId = :followupClaimedTickBatchId)
        and (:followupRequiresSoloTick is null
             or linkedFollowup.requiresSoloTick = :followupRequiresSoloTick)
        and (:followupQueueSourceKind = ''
             or linkedFollowup.queueSourceKind = :followupQueueSourceKind)
        and (:followupQueueSourceState = ''
             or linkedFollowup.queueSourceState = :followupQueueSourceState)
        and (:followupQueueSourceOrdinal <= 0
             or linkedFollowup.queueSourceOrdinal = :followupQueueSourceOrdinal)
        and (:followupQueueSourceDueTickId <= 0
             or linkedFollowup.queueSourceDueTickId = :followupQueueSourceDueTickId)
        and (:followupQueueSourceDueAtMs <= 0
             or linkedFollowup.queueSourceDueAtMs = :followupQueueSourceDueAtMs)
        and (:targetCommandId = '' or targetCommand.commandId = :targetCommandId)
        and (:targetCommandExecutionOutcome = ''
             or targetCommand.executionOutcome = :targetCommandExecutionOutcome)
        and (:targetCommandGameplayResult = ''
             or targetCommand.gameplayResult = :targetCommandGameplayResult)
        and (:latestResultOutcome = ''
             or exists (
               select 1 from RemoteFollowupResult result
               where result.tenantId = coordinator.tenantId
                 and result.coordinatorId = coordinator.coordinatorId
                 and result.outcome = :latestResultOutcome
                 and result.observedAt = (
                   select max(latest.observedAt) from RemoteFollowupResult latest
                   where latest.tenantId = coordinator.tenantId
                     and latest.coordinatorId = coordinator.coordinatorId)))
        and (:latestResultErrorCode = ''
             or exists (
               select 1 from RemoteFollowupResult result
               where result.tenantId = coordinator.tenantId
                 and result.coordinatorId = coordinator.coordinatorId
                 and result.resultErrorCode = :latestResultErrorCode
                 and result.observedAt = (
                   select max(latest.observedAt) from RemoteFollowupResult latest
                   where latest.tenantId = coordinator.tenantId
                     and latest.coordinatorId = coordinator.coordinatorId)))
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
      @Param("currentOriginRuntimeRegionId") String currentOriginRuntimeRegionId,
      @Param("currentOriginRuntimeRegionEpoch") long currentOriginRuntimeRegionEpoch,
      @Param("currentOriginRuntimeGameInstanceId") Long currentOriginRuntimeGameInstanceId,
      @Param("currentTargetRuntimeRegionId") String currentTargetRuntimeRegionId,
      @Param("currentTargetRuntimeRegionEpoch") long currentTargetRuntimeRegionEpoch,
      @Param("currentTargetRuntimeGameInstanceId") Long currentTargetRuntimeGameInstanceId,
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
      @Param("claimTargetAggregate") String claimTargetAggregate,
      @Param("effectKey") String effectKey,
      @Param("payloadKind") String payloadKind,
      @Param("originSourceKind") String originSourceKind,
      @Param("originSourceState") String originSourceState,
      @Param("automationWorkItemId") String automationWorkItemId,
      @Param("eventType") String eventType,
      @Param("scriptEventId") String scriptEventId,
      @Param("lateResultPolicy") String lateResultPolicy,
      @Param("executionOutcome") String executionOutcome,
      @Param("gameplayResult") String gameplayResult,
      @Param("followupStatus") String followupStatus,
      @Param("followupClaimedTickBatchId") String followupClaimedTickBatchId,
      @Param("followupRequiresSoloTick") Boolean followupRequiresSoloTick,
      @Param("followupQueueSourceKind") String followupQueueSourceKind,
      @Param("followupQueueSourceState") String followupQueueSourceState,
      @Param("followupQueueSourceOrdinal") long followupQueueSourceOrdinal,
      @Param("followupQueueSourceDueTickId") long followupQueueSourceDueTickId,
      @Param("followupQueueSourceDueAtMs") long followupQueueSourceDueAtMs,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      @Param("targetCommandId") String targetCommandId,
      @Param("targetCommandExecutionOutcome") String targetCommandExecutionOutcome,
      @Param("targetCommandGameplayResult") String targetCommandGameplayResult,
      @Param("latestResultOutcome") String latestResultOutcome,
      @Param("latestResultErrorCode") String latestResultErrorCode,
      Pageable pageable);
}
