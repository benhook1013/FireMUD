package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;

public interface ScriptWorkItemService {
  long cancelPendingForPatch(CancelPendingForPatchCommand command);

  List<ScriptWorkItem> claimPendingForEvaluation(int maxItems);

  record CancelPendingForPatchCommand(
      String tenantId,
      String scriptPatchVersion,
      String gameInstanceId,
      String regionId,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason) {}
}
