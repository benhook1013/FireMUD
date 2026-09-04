package net.firedevops.firemud.automationscripting.service;

import java.util.Optional;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;

public interface ScriptPatchPinProjectionService {
  PinConvergenceLookup getPinConvergence(String tenantId, String gameInstanceId);

  void observeRuntimeState(
      String tenantId, String gameInstanceId, GameInstanceRuntimeState runtimeState);

  record PinConvergenceLookup(
      Optional<PinConvergenceSummary> summary, String errorCode, String errorMessage) {}

  record PinConvergenceSummary(
      String tenantId,
      String gameInstanceId,
      String observedPinnedScriptPatchVersion,
      long scriptPinEpoch,
      String lastObservedControlPlaneRequestId,
      long observedAtMs,
      long projectionAsOfMs,
      long projectionLagMs,
      boolean projectionStale,
      String runtimeRegionId,
      long runtimeRegionEpoch,
      String worldSlug,
      String realmSlug,
      String pointerVersion) {
    public PinConvergenceSummary(
        String tenantId,
        String gameInstanceId,
        String observedPinnedScriptPatchVersion,
        String lastObservedControlPlaneRequestId,
        long observedAtMs,
        long projectionAsOfMs,
        long projectionLagMs,
        boolean projectionStale,
        String runtimeRegionId,
        long runtimeRegionEpoch,
        String worldSlug,
        String realmSlug,
        String pointerVersion) {
      this(
          tenantId,
          gameInstanceId,
          observedPinnedScriptPatchVersion,
          0L,
          lastObservedControlPlaneRequestId,
          observedAtMs,
          projectionAsOfMs,
          projectionLagMs,
          projectionStale,
          runtimeRegionId,
          runtimeRegionEpoch,
          worldSlug,
          realmSlug,
          pointerVersion);
    }
  }
}
