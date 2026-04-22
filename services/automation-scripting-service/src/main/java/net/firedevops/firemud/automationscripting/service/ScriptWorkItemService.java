package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchStatus;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  List<ScriptWorkItem> claimPendingForEvaluation(int maxItems);

  TerminalCleanupResult cleanupTerminalWorkItems();

  Optional<PatchStatusSummary> getPatchStatus(String tenantId, String scriptPatchVersion);

  List<PatchStatusSummary> listPatchStatuses(
      String tenantId, ScriptPatchStatus status, long changedAfterMs, long changedBeforeMs);

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
