package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;

public interface ScriptPatchInstanceRolloutProjectionService {
  Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId, String gameInstanceId, String scriptPatchVersion, long scriptPinEpoch);

  Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId);

  default Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId, String gameInstanceId, String scriptPatchVersion) {
    return getProjection(tenantId, gameInstanceId, scriptPatchVersion, 0L);
  }

  List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  default List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs) {
    return listProjections(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        0L,
        rolloutStatus,
        changedAfterMs,
        changedBeforeMs);
  }

  List<ScriptWorkItemService.PatchInstanceRolloutEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  List<ScriptWorkItemService.PatchInstanceRolloutEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

  default List<ScriptWorkItemService.PatchInstanceRolloutEventSummary> listEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs,
      int limit) {
    return listEvents(
        tenantId,
        gameInstanceId,
        scriptPatchVersion,
        0L,
        rolloutStatus,
        changedAfterMs,
        changedBeforeMs,
        limit);
  }

  void refreshForWorkItem(ScriptWorkItem workItem);

  void refreshForInstance(String tenantId, String gameInstanceId);
}
