package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;

public interface ScriptPatchInstanceRolloutProjectionService {
  Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId, String gameInstanceId, String scriptPatchVersion);

  List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  List<ScriptWorkItemService.PatchInstanceRolloutEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  void refreshForWorkItem(ScriptWorkItem workItem);

  void refreshForInstance(String tenantId, String gameInstanceId);
}
