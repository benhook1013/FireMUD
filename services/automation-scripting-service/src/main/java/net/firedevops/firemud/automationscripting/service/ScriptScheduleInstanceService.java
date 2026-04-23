package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;

public interface ScriptScheduleInstanceService {
  void reconcileObservedRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState);

  void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion);

  List<ScheduleInstanceSummary> listInstances(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit);

  record ScheduleInstanceSummary(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String scriptId,
      String pluginId,
      String pluginVersionId,
      String eventType,
      String scheduleDefinitionId,
      String scheduleKind,
      long cadenceValue,
      String cadenceUnit,
      String priorityTag,
      String materializationStatus,
      long nextDueAtMs,
      long nextDueTickId,
      String observedRuntimeVersionId,
      String lastObservedControlPlaneRequestId,
      long pinObservedAtMs,
      long materializedAtMs,
      long updatedAtMs) {}
}
