package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService.PluginPublicationLink;
import net.firedevops.firemud.automationscripting.service.ScriptPatchReadinessProjectionService.ReadinessStatusSummary;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  long cancelPendingForPluginVersion(CancelPendingForPluginVersionCommand command);

  List<ScriptWorkItem> claimPendingForEvaluation(int maxItems);

  List<ScriptWorkItem> claimPendingForEvaluation(List<Long> workItemIds, int maxItems);

  TerminalCleanupResult cleanupTerminalWorkItems();

  Optional<PatchStatusSummary> getPatchStatus(String tenantId, String scriptPatchVersion);

  List<PatchStatusSummary> listPatchStatuses(
      String tenantId, ScriptPatchStatus status, long changedAfterMs, long changedBeforeMs);

  AutomationDrainStatusSummary getAutomationDrainStatus(
      String tenantId, String gameInstanceId, String regionId);

  Optional<PatchInstanceRolloutSummary> getPatchInstanceRolloutStatus(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId);

  default Optional<PatchInstanceRolloutSummary> getPatchInstanceRolloutStatus(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    return getPatchInstanceRolloutStatus(tenantId, gameInstanceId, scriptPatchVersion, 0L, null);
  }

  List<PatchInstanceRolloutSummary> listPatchInstanceRollouts(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  default List<PatchInstanceRolloutSummary> listPatchInstanceRollouts(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs) {
    return listPatchInstanceRollouts(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        0L,
        null,
        rolloutStatus,
        changedAfterMs,
        changedBeforeMs);
  }

  List<PatchInstanceRolloutEventSummary> listPatchInstanceRolloutEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  default List<PatchInstanceRolloutEventSummary> listPatchInstanceRolloutEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    return listPatchInstanceRolloutEvents(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        0L,
        null,
        rolloutStatus,
        changedAfterMs,
        changedBeforeMs,
        limit);
  }

  List<HandoffEventSummary> listHandoffEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String workItemId,
      String handoffOutcome,
      String targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String scriptId,
      String pluginId,
      String automationDispatchId,
      String gameSessionCommandId,
      String targetEntityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String sourceKind,
      String sourceState,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  List<DeadLetterSummary> listDeadLetters(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit);

  ReplayResult replayDeadLetters(ReplayDeadLettersCommand command);

  record CancelPendingForPatchCommand(
      String tenantId,
      String scriptPatchVersion,
      String gameInstanceId,
      String regionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record CancelPendingForPluginVersionCommand(
      String tenantId,
      String pluginId,
      String pluginVersionId,
      String gameInstanceId,
      String regionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}

  record TerminalCleanupResult(
      long handedOffDeleted, long canceledDeleted, long deadLetteredDeleted) {
    public long totalDeleted() {
      return handedOffDeleted + canceledDeleted + deadLetteredDeleted;
    }
  }

  record PatchStatusSummary(
      String scriptPatchVersion,
      ScriptPatchStatus status,
      String statusReason,
      String supersededByScriptPatchVersion,
      long lastChangedAtMs,
      long baseVersionId,
      String abilitySchemaDigest,
      ScriptPatchPublicationLink publication) {
    public static PatchStatusSummary fromProjection(
        ReadinessStatusSummary readiness,
        long baseVersionId,
        String abilitySchemaDigest,
        ScriptPatchPublicationLink publication) {
      return new PatchStatusSummary(
          readiness.scriptPatchVersion(),
          readiness.status(),
          readiness.statusReason(),
          readiness.supersededByScriptPatchVersion(),
          readiness.lastChangedAtMs(),
          baseVersionId,
          abilitySchemaDigest,
          publication);
    }
  }

  record ScriptPatchPublicationLink(
      String scriptPatchVersion,
      long versionId,
      long baseVersionId,
      VersionLifecycleState publicationState,
      long lastChangedAtMs,
      String lookupErrorCode,
      String lookupErrorMessage) {}

  record AutomationDrainStatusSummary(
      String tenantId,
      String gameInstanceId,
      String regionId,
      String admissionMode,
      long admissionEpoch,
      long activeExecutionCount,
      long oldestActiveExecutionStartedAtMs,
      long pendingCancelableWorkItemCount,
      long observedAtMs) {}

  record PatchInstanceRolloutSummary(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long lastChangedAtMs,
      long projectionAsOfMs,
      long projectionLagMs,
      boolean projectionStale,
      ScriptPatchPublicationLink publication) {
    public PatchInstanceRolloutSummary(
        String tenantId,
        String gameInstanceId,
        String scriptPatchVersion,
        ScriptPatchInstanceRolloutStatus rolloutStatus,
        String statusReason,
        long lastChangedAtMs,
        long projectionAsOfMs,
        long projectionLagMs,
        boolean projectionStale,
        ScriptPatchPublicationLink publication) {
      this(
          tenantId,
          gameInstanceId,
          scriptPatchVersion,
          0L,
          "",
          rolloutStatus,
          statusReason,
          lastChangedAtMs,
          projectionAsOfMs,
          projectionLagMs,
          projectionStale,
          publication);
    }
  }

  record PatchInstanceRolloutEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long observedAtMs,
      long projectionAsOfMs,
      ScriptPatchPublicationLink publication) {
    public PatchInstanceRolloutEventSummary(
        String eventId,
        String tenantId,
        String gameInstanceId,
        String scriptPatchVersion,
        ScriptPatchInstanceRolloutStatus rolloutStatus,
        String statusReason,
        long observedAtMs,
        long projectionAsOfMs,
        ScriptPatchPublicationLink publication) {
      this(
          eventId,
          tenantId,
          gameInstanceId,
          scriptPatchVersion,
          0L,
          "",
          rolloutStatus,
          statusReason,
          observedAtMs,
          projectionAsOfMs,
          publication);
    }
  }

  record HandoffEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      String scriptId,
      String pluginId,
      String pluginVersionId,
      String workItemId,
      int commandOrdinal,
      String automationDispatchId,
      String gameSessionCommandId,
      String targetGameInstanceId,
      String targetRegionId,
      long targetRegionEpoch,
      String remoteCoordinatorId,
      String remoteFollowupId,
      String targetEntityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String sourceKind,
      String sourceState,
      long sourceOrdinal,
      long sourceDueTickId,
      long sourceDueAtMs,
      String emittedCommandText,
      String handoffOutcome,
      String handoffReason,
      long observedAtMs,
      ScriptPatchPublicationLink publication,
      PluginPublicationLink pluginPublication) {}

  record DeadLetterSummary(
      String workItemId,
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String sourceKind,
      String sourceState,
      long sourceOrdinal,
      long sourceDueTickId,
      long sourceDueAtMs,
      String scriptId,
      String pluginId,
      String pluginVersionId,
      String eventType,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
      String scriptEventId,
      String status,
      String reason,
      long createdAtMs,
      long updatedAtMs,
      ScriptPatchPublicationLink publication,
      PluginPublicationLink pluginPublication) {}

  record ReplayDeadLettersCommand(
      String tenantId,
      String gameInstanceId,
      String regionId,
      List<String> workItemIds,
      String scriptPatchVersion,
      long createdAfterMs,
      long createdBeforeMs,
      int limit,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {
    public ReplayDeadLettersCommand {
      workItemIds = workItemIds == null ? List.of() : List.copyOf(workItemIds);
    }
  }

  record ReplayResult(long replayedCount, long rejectedCount) {}
}
