package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;

public interface ScriptScheduleInstanceService {
  void reconcileObservedRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState);

  void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion);

  RuntimeTickProgressResult observeRuntimeTickProgress(RuntimeTickProgressObservation observation);

  List<ScheduleInstanceSummary> listInstances(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit);

  record RuntimeTickProgressObservation(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      long tickId,
      long observedAtMs) {}

  record RuntimeTickProgressResult(
      int updatedScheduleCount, int firedScheduleCount, int truncatedFiringCount) {}

  record ScheduleInstanceSummary(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String scriptId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String pluginId,
      String pluginVersionId,
      String eventType,
      String scheduleDefinitionId,
      String scheduleKind,
      long cadenceValue,
      String cadenceUnit,
      String priorityTag,
      String targetScopeType,
      String targetScopeId,
      int bindingPriority,
      boolean requiresExclusiveEvent,
      String materializationStatus,
      long nextDueAtMs,
      long nextDueTickId,
      String observedRuntimeVersionId,
      String lastObservedControlPlaneRequestId,
      long pinObservedAtMs,
      long materializedAtMs,
      long updatedAtMs,
      String runtimeRegionId,
      long runtimeRegionEpoch,
      long lastObservedTickId,
      long lastRuntimeProgressObservedAtMs) {}
}
