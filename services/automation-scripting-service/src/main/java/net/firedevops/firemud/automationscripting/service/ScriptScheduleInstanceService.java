package net.firedevops.firemud.automationscripting.service;

import java.util.List;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService.ScriptPatchPublicationLink;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;

public interface ScriptScheduleInstanceService {
  void reconcileObservedRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState);

  void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion);

  RuntimeTickProgressResult observeRuntimeTickProgress(RuntimeTickProgressObservation observation);

  List<ScheduleInstanceSummary> listInstances(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit);

  List<TimerAuditEventSummary> listTimerAuditEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      String scriptId,
      String eventType,
      String finalReason,
      long changedAfterMs,
      long changedBeforeMs,
      int limit);

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
      long lastRuntimeProgressObservedAtMs,
      ScriptPatchPublicationLink publication) {}

  record TimerAuditEventSummary(
      String tenantId,
      String gameInstanceId,
      String regionId,
      long regionEpoch,
      String entityId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String scriptId,
      String pluginId,
      String pluginVersionId,
      String eventType,
      String scriptPatchVersion,
      String scriptEventId,
      String triggerMode,
      String sourceState,
      long sourceOrdinal,
      long sourceDueTickId,
      long sourceDueAtMs,
      long workItemId,
      String finalStage,
      String finalOutcome,
      String finalReason,
      long createdAtMs,
      long updatedAtMs,
      ScriptPatchPublicationLink publication) {}
}
