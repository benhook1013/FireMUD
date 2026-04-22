package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  List<ScriptWorkItem> claimPendingForEvaluation(int maxItems);

  TerminalCleanupResult cleanupTerminalWorkItems();

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
}
