package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  long cancelPendingForPluginVersion(CancelPendingForPluginVersionCommand command);

  List<ScriptWorkItem> claimPendingForEvaluation(int maxItems);

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
      long lastChangedAtMs) {}

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
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long lastChangedAtMs,
      long projectionAsOfMs,
      long projectionLagMs,
      boolean projectionStale) {}

  record PatchInstanceRolloutEventSummary(
      String eventId,
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      String statusReason,
      long observedAtMs,
      long projectionAsOfMs) {}

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
      String targetEntityId,
      String handoffOutcome,
      String handoffReason,
      long observedAtMs) {}

  record DeadLetterSummary(
      String workItemId,
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      String scriptId,
      String eventType,
      String scriptPatchVersion,
      String scriptEventId,
      String status,
      String reason,
      long createdAtMs,
      long updatedAtMs) {}

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
