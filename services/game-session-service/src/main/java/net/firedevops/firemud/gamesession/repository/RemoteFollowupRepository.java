package net.firedevops.firemud.gamesession.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.RemoteFollowup;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteFollowupRepository extends JpaRepository<RemoteFollowup, Long> {
  Optional<RemoteFollowup> findByFollowupId(String followupId);

  Optional<RemoteFollowup> findByTenantIdAndFollowupId(Long tenantId, String followupId);

  List<RemoteFollowup> findByTenantIdAndFollowupIdIn(
      Long tenantId, java.util.Collection<String> ids);

  Optional<RemoteFollowup> findByTenantIdAndTargetRegionIdAndTargetRegionEpochAndEffectKey(
      Long tenantId, String targetRegionId, long targetRegionEpoch, String effectKey);

  long countByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqual(
      Long tenantId, String targetRegionId, String status, long dueTickId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  List<RemoteFollowup>
      findByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAscIdAsc(
          Long tenantId, String targetRegionId, String status, long dueTickId, Pageable pageable);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdAndStatusOrderByDueTickIdAscIdAsc(
      Long tenantId, String targetRegionId, String status);

  Optional<RemoteFollowup>
      findFirstByTenantIdAndTargetRegionIdAndStatusAndDueTickIdLessThanEqualOrderByDueTickIdAsc(
          Long tenantId, String targetRegionId, String status, long dueTickId);

  List<RemoteFollowup> findByTenantIdAndTargetRegionIdOrderByDueTickIdAsc(
      Long tenantId, String targetRegionId);

  List<RemoteFollowup> findByClaimedTickBatchIdOrderByIdAsc(String claimedTickBatchId);

  @Query(
      """
      select followup from RemoteFollowup followup
      left join GameplayCommand targetCommand
        on targetCommand.tenantId = followup.tenantId
       and targetCommand.remoteFollowupId = followup.followupId
      where followup.tenantId = :tenantId
        and (:targetRegionId = '' or followup.targetRegionId = :targetRegionId)
        and (:status = '' or followup.status = :status)
        and (:originGameInstanceId is null or followup.originGameInstanceId = :originGameInstanceId)
        and (:originRegionId = '' or followup.originRegionId = :originRegionId)
        and (:originRegionEpoch <= 0 or followup.originRegionEpoch = :originRegionEpoch)
        and (:targetGameInstanceId is null or followup.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionEpoch <= 0 or followup.targetRegionEpoch = :targetRegionEpoch)
        and (:followupId = '' or followup.followupId = :followupId)
        and (:scriptId = '' or followup.scriptId = :scriptId)
        and (:pluginId = '' or followup.pluginId = :pluginId)
        and (:scriptPatchVersion = '' or followup.scriptPatchVersion = :scriptPatchVersion)
        and (:pluginVersionId = '' or followup.pluginVersionId = :pluginVersionId)
        and (:playableStateScope = '' or followup.playableStateScope = :playableStateScope)
        and (:worldSlug = '' or followup.worldSlug = :worldSlug)
        and (:realmSlug = '' or followup.realmSlug = :realmSlug)
        and (:pointerVersion is null or followup.pointerVersion = :pointerVersion)
        and (:payloadKind = '' or followup.payloadKind = :payloadKind)
        and (:originSourceKind = '' or followup.originSourceKind = :originSourceKind)
        and (:originSourceState = '' or followup.originSourceState = :originSourceState)
        and (:automationWorkItemId = '' or followup.automationWorkItemId = :automationWorkItemId)
        and (:targetEntityId = '' or followup.targetEntityId = :targetEntityId)
        and (:claimTargetAggregate = ''
             or followup.claimTargetAggregate = :claimTargetAggregate)
        and (:effectKey = '' or followup.effectKey = :effectKey)
        and (:failureCode = '' or followup.failureCode = :failureCode)
        and (:requiresSoloTick is null or followup.requiresSoloTick = :requiresSoloTick)
        and (:claimedTickBatchId = '' or followup.claimedTickBatchId = :claimedTickBatchId)
        and (:requestedCommand = '' or followup.requestedCommand = :requestedCommand)
        and (:eventType = '' or followup.eventType = :eventType)
        and (:scriptEventId = '' or followup.scriptEventId = :scriptEventId)
        and (:automationDispatchId = '' or followup.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or followup.commandId = :commandId)
        and (:targetCommandId = '' or targetCommand.commandId = :targetCommandId)
        and (:targetCommandExecutionOutcome = ''
             or targetCommand.executionOutcome = :targetCommandExecutionOutcome)
        and (:targetCommandGameplayResult = ''
             or targetCommand.gameplayResult = :targetCommandGameplayResult)
        and (:originDeadlineRegionEpoch <= 0
             or exists (
               select 1 from RemoteCommandCoordinator coordinator
               where coordinator.tenantId = followup.tenantId
                 and coordinator.followupId = followup.followupId
                 and coordinator.originDeadlineRegionEpoch = :originDeadlineRegionEpoch))
        and (:originDeadlineTickId <= 0
             or exists (
               select 1 from RemoteCommandCoordinator coordinator
               where coordinator.tenantId = followup.tenantId
                 and coordinator.followupId = followup.followupId
                 and coordinator.originDeadlineTickId = :originDeadlineTickId))
        and (:lateResultPolicy = ''
             or exists (
               select 1 from RemoteCommandCoordinator coordinator
               where coordinator.tenantId = followup.tenantId
                 and coordinator.followupId = followup.followupId
                 and coordinator.lateResultPolicy = :lateResultPolicy))
      order by followup.dueTickId asc, followup.id asc
      """)
  List<RemoteFollowup> findForControlPlane(
      @Param("tenantId") Long tenantId,
      @Param("targetRegionId") String targetRegionId,
      @Param("status") String status,
      @Param("originGameInstanceId") Long originGameInstanceId,
      @Param("originRegionId") String originRegionId,
      @Param("originRegionEpoch") long originRegionEpoch,
      @Param("targetGameInstanceId") Long targetGameInstanceId,
      @Param("targetRegionEpoch") long targetRegionEpoch,
      @Param("followupId") String followupId,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("pluginVersionId") String pluginVersionId,
      @Param("playableStateScope") String playableStateScope,
      @Param("worldSlug") String worldSlug,
      @Param("realmSlug") String realmSlug,
      @Param("pointerVersion") Long pointerVersion,
      @Param("payloadKind") String payloadKind,
      @Param("originSourceKind") String originSourceKind,
      @Param("originSourceState") String originSourceState,
      @Param("automationWorkItemId") String automationWorkItemId,
      @Param("targetEntityId") String targetEntityId,
      @Param("claimTargetAggregate") String claimTargetAggregate,
      @Param("effectKey") String effectKey,
      @Param("failureCode") String failureCode,
      @Param("requiresSoloTick") Boolean requiresSoloTick,
      @Param("claimedTickBatchId") String claimedTickBatchId,
      @Param("requestedCommand") String requestedCommand,
      @Param("eventType") String eventType,
      @Param("scriptEventId") String scriptEventId,
      @Param("originDeadlineRegionEpoch") long originDeadlineRegionEpoch,
      @Param("originDeadlineTickId") long originDeadlineTickId,
      @Param("lateResultPolicy") String lateResultPolicy,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      @Param("targetCommandId") String targetCommandId,
      @Param("targetCommandExecutionOutcome") String targetCommandExecutionOutcome,
      @Param("targetCommandGameplayResult") String targetCommandGameplayResult,
      Pageable pageable);
}
