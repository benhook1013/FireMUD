package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.entity.ScriptWorkItem;
import net.firedevops.firemud.automationscripting.v1.ScriptPatchInstanceRolloutStatus;

/** Diagnostic rollout projection reads fenced by the observed Game Session pin owner tuple. */
public interface ScriptPatchInstanceRolloutProjectionService {
  /**
   * Reads one projection. A zero pin epoch with a blank request id is the explicit unfiltered
   * sentinel; a positive epoch requires the corresponding nonblank owner request id.
   */
  Optional<ScriptWorkItemService.PatchInstanceRolloutSummary> getProjection(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId);

  /** Uses the same zero/blank unfiltered sentinel and positive-epoch complete-tuple rule. */
  List<ScriptWorkItemService.PatchInstanceRolloutSummary> listProjections(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      ScriptPatchInstanceRolloutStatus rolloutStatus,
      long changedAfterMs,
      long changedBeforeMs);

  /** Uses the same zero/blank unfiltered sentinel and positive-epoch complete-tuple rule. */
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

  void refreshForWorkItem(ScriptWorkItem workItem);

  void refreshForInstance(String tenantId, String gameInstanceId);
}
