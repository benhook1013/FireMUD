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
}
