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
      String tenantId, String gameInstanceId, String scriptPatchVersion);

  List<PatchInstanceRolloutSummary> listPatchInstanceRollouts(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  List<PatchInstanceRolloutEventSummary> listPatchInstanceRolloutEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

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
      boolean statePresent,
      String admissionMode,
      long admissionEpoch,
      String controlPlaneRequestId,
      String targetMode,
      String outcome,
      String requestFingerprint,
      long acknowledgedAtMs,
      long activeExecutionCount,
      long oldestActiveExecutionStartedAtMs,
      long pendingCancelableWorkItemCount,
      long observedAtMs) {
    public AutomationDrainStatusSummary(
        String tenantId,
        String gameInstanceId,
        String regionId,
        String admissionMode,
        long admissionEpoch,
        long activeExecutionCount,
        long oldestActiveExecutionStartedAtMs,
        long pendingCancelableWorkItemCount,
        long observedAtMs) {
      this(
          tenantId,
          gameInstanceId,
          regionId,
          true,
          admissionMode,
          admissionEpoch,
          "",
          "",
          AutomationAdmissionStateService.OUTCOME_ACKNOWLEDGEMENT_UNAVAILABLE,
          "",
          0L,
          activeExecutionCount,
          oldestActiveExecutionStartedAtMs,
          pendingCancelableWorkItemCount,
          observedAtMs);
    }
  }

  record PatchInstanceRolloutSummary(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long lastChangedAtMs,
      long projectionAsOfMs,
      long projectionLagMs,
      boolean projectionStale,
      ScriptPatchPublicationLink publication) {}

  record PatchInstanceRolloutEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long observedAtMs,
      long projectionAsOfMs,
      ScriptPatchPublicationLink publication) {}

  record HandoffEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
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

  record ReplayItemResult(
      String workItemId,
      String outcome,
      String rejectionReason,
      String failureReason,
      long failureGeneration) {
    public ReplayItemResult(
        String workItemId, String outcome, String rejectionReason, long failureGeneration) {
      this(workItemId, outcome, rejectionReason, "", failureGeneration);
    }

    public ReplayItemResult(String workItemId, String outcome, String rejectionReason) {
      this(workItemId, outcome, rejectionReason, "", 0L);
    }
  }

  record ReplayResult(
      long replayedCount,
      long rejectedCount,
      List<ReplayItemResult> results,
      String requestFingerprint) {
    public ReplayResult {
      results = results == null ? List.of() : List.copyOf(results);
      requestFingerprint = requestFingerprint == null ? "" : requestFingerprint;
    }

    /** Compatibility constructor for callers that only consume aggregate counts. */
    public ReplayResult(long replayedCount, long rejectedCount) {
      this(replayedCount, rejectedCount, List.of(), "");
    }
  }
}
