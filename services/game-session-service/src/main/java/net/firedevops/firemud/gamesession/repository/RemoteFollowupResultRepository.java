package net.firedevops.firemud.gamesession.repository;

import java.util.List;
import net.firedevops.firemud.gamesession.entity.RemoteFollowupResult;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RemoteFollowupResultRepository extends JpaRepository<RemoteFollowupResult, Long> {
  java.util.Optional<RemoteFollowupResult> findByTenantIdAndResultId(
      Long tenantId, String resultId);

  java.util.Optional<RemoteFollowupResult> findFirstByTenantIdAndCoordinatorIdOrderByObservedAtDesc(
      Long tenantId, String coordinatorId);

  List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdOrderByObservedAtAsc(
      Long tenantId, String coordinatorId);

  List<RemoteFollowupResult> findByTenantIdAndCoordinatorIdInOrderByObservedAtAsc(
      Long tenantId, java.util.Collection<String> coordinatorIds);

  @Query(
      """
      select result from RemoteFollowupResult result
      left join RemoteFollowup linkedFollowup
        on linkedFollowup.tenantId = result.tenantId
       and linkedFollowup.followupId = result.followupId
      left join GameplayCommand resultCommand
        on resultCommand.commandId = result.resultCommandId
      where result.tenantId = :tenantId
        and (:coordinatorId = '' or result.coordinatorId = :coordinatorId)
        and (:followupId = '' or result.followupId = :followupId)
        and (:originGameInstanceId is null or result.originGameInstanceId = :originGameInstanceId)
        and (:originRegionId = '' or result.originRegionId = :originRegionId)
        and (:originRegionEpoch <= 0 or result.originRegionEpoch = :originRegionEpoch)
        and (:targetGameInstanceId is null or result.targetGameInstanceId = :targetGameInstanceId)
        and (:targetRegionId = '' or result.targetRegionId = :targetRegionId)
        and (:targetRegionEpoch <= 0 or result.targetRegionEpoch = :targetRegionEpoch)
        and (:outcome = '' or result.outcome = :outcome)
        and (:scriptId = '' or result.scriptId = :scriptId)
        and (:pluginId = '' or result.pluginId = :pluginId)
        and (:scriptPatchVersion = '' or result.scriptPatchVersion = :scriptPatchVersion)
        and (:pluginVersionId = '' or result.pluginVersionId = :pluginVersionId)
        and (:playableStateScope = '' or result.playableStateScope = :playableStateScope)
        and (:worldSlug = '' or result.worldSlug = :worldSlug)
        and (:realmSlug = '' or result.realmSlug = :realmSlug)
        and (:pointerVersion is null or result.pointerVersion = :pointerVersion)
        and (:resultErrorCode = '' or result.resultErrorCode = :resultErrorCode)
        and (:automationWorkItemId = '' or result.automationWorkItemId = :automationWorkItemId)
        and (:resultCommandId = '' or result.resultCommandId = :resultCommandId)
        and (:resultCommandExecutionOutcome = ''
             or resultCommand.executionOutcome = :resultCommandExecutionOutcome)
        and (:resultCommandGameplayResult = ''
             or resultCommand.gameplayResult = :resultCommandGameplayResult)
        and (:targetEntityId = '' or linkedFollowup.targetEntityId = :targetEntityId)
        and (:effectKey = '' or linkedFollowup.effectKey = :effectKey)
        and (:failureCode = '' or linkedFollowup.failureCode = :failureCode)
        and (:payloadKind = '' or linkedFollowup.payloadKind = :payloadKind)
        and (:originSourceKind = '' or linkedFollowup.originSourceKind = :originSourceKind)
        and (:eventType = '' or linkedFollowup.eventType = :eventType)
        and (:scriptEventId = '' or linkedFollowup.scriptEventId = :scriptEventId)
        and (:automationDispatchId = '' or result.automationDispatchId = :automationDispatchId)
        and (:commandId = '' or result.commandId = :commandId)
      order by result.observedAt asc, result.id asc
      """)
  List<RemoteFollowupResult> findForControlPlane(
      @Param("tenantId") Long tenantId,
      @Param("coordinatorId") String coordinatorId,
      @Param("followupId") String followupId,
      @Param("originGameInstanceId") Long originGameInstanceId,
      @Param("originRegionId") String originRegionId,
      @Param("originRegionEpoch") long originRegionEpoch,
      @Param("targetGameInstanceId") Long targetGameInstanceId,
      @Param("targetRegionId") String targetRegionId,
      @Param("targetRegionEpoch") long targetRegionEpoch,
      @Param("outcome") String outcome,
      @Param("scriptId") String scriptId,
      @Param("pluginId") String pluginId,
      @Param("scriptPatchVersion") String scriptPatchVersion,
      @Param("pluginVersionId") String pluginVersionId,
      @Param("playableStateScope") String playableStateScope,
      @Param("worldSlug") String worldSlug,
      @Param("realmSlug") String realmSlug,
      @Param("pointerVersion") Long pointerVersion,
      @Param("resultErrorCode") String resultErrorCode,
      @Param("automationWorkItemId") String automationWorkItemId,
      @Param("resultCommandId") String resultCommandId,
      @Param("resultCommandExecutionOutcome") String resultCommandExecutionOutcome,
      @Param("resultCommandGameplayResult") String resultCommandGameplayResult,
      @Param("targetEntityId") String targetEntityId,
      @Param("effectKey") String effectKey,
      @Param("failureCode") String failureCode,
      @Param("payloadKind") String payloadKind,
      @Param("originSourceKind") String originSourceKind,
      @Param("eventType") String eventType,
      @Param("scriptEventId") String scriptEventId,
      @Param("automationDispatchId") String automationDispatchId,
      @Param("commandId") String commandId,
      Pageable pageable);
}
