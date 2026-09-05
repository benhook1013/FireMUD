package net.firedevops.firemud.automationscripting.service;

import java.time.Instant;
import java.util.List;
import net.firedevops.firemud.automationscripting.service.PluginRuntimeStateService.PluginPublicationLink;
import net.firedevops.firemud.automationscripting.service.ScriptWorkItemService.ScriptPatchPublicationLink;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;

public interface ScriptScheduleInstanceService {
  default void reconcileObservedRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState) {
    reconcileObservedRuntimeState(tenantId, gameInstanceId, runtimeState, null);
  }

  /**
   * Reconciles schedules using a non-pin transition commit timestamp when applicable. A null seed
   * means this is ordinary Game Session pin materialization and uses the pin timestamp.
   */
  default void reconcileObservedRuntimeState(
      String tenantId,
      String gameInstanceId,
      GameInstanceRuntimeState runtimeState,
      Instant nonPinTransitionSeed) {
    reconcileObservedRuntimeState(tenantId, gameInstanceId, runtimeState, nonPinTransitionSeed, "");
  }

  /**
   * Reconciles a plugin transition while identifying the plugin whose transition seed applies. A
   * non-null transition seed fans out to every matching materialized schedule instance in the
   * selected scope: a blank transitionPluginId selects all rows, while a nonblank value selects
   * only that plugin's rows. Other plugin-owned schedule rows retain their existing due state. A
   * null transitionPluginId is invalid; callers must use an empty string when selecting all rows. A
   * null transition seed is valid and means no non-pin transition reset is requested.
   *
   * @throws IllegalArgumentException if transitionPluginId is null
   */
  void reconcileObservedRuntimeState(
      String tenantId,
      String gameInstanceId,
      GameInstanceRuntimeState runtimeState,
      Instant nonPinTransitionSeed,
      String transitionPluginId);

  void reconcilePinnedPatchInstances(String tenantId, String scriptPatchVersion);

  RuntimeTickProgressResult observeRuntimeTickProgress(RuntimeTickProgressObservation observation);

  List<ScheduleInstanceSummary> listInstances(
      String tenantId, String gameInstanceId, String scriptPatchVersion, int limit);

  /**
   * Lists timer audit events with an optional exact pin filter. Epoch zero together with a blank
   * request ID means unfiltered; a positive epoch requires a matching nonblank request ID, and a
   * negative epoch is invalid.
   */
  List<TimerAuditEventSummary> listTimerAuditEvents(
      String tenantId,
      String gameInstanceId,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
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
      long scriptPinEpoch,
      String scriptId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      String pointerVersion,
      String pluginId,
      String pluginVersionId,
      String bindingId,
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
      ScriptPatchPublicationLink publication,
      PluginPublicationLink pluginPublication) {}

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
      String bindingId,
      String eventType,
      String scriptPatchVersion,
      long scriptPinEpoch,
      String scriptPinControlPlaneRequestId,
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
      ScriptPatchPublicationLink publication,
      PluginPublicationLink pluginPublication) {}
}
