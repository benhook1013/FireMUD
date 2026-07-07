package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.automationscripting.client.GameSessionControlPlaneClient;
import net.firedevops.firemud.automationscripting.entity.PluginRuntimeState;
import net.firedevops.firemud.gamesession.v1.GetGameInstanceRuntimeStateResponse;

final class AutomationRuntimeScopeSupport {
  private AutomationRuntimeScopeSupport() {}

  static RuntimeScope currentRuntimeScope(
      GameSessionControlPlaneClient gameSessionControlPlaneClient,
      String tenantId,
      String gameInstanceId,
      String preferredRegionId) {
    GetGameInstanceRuntimeStateResponse runtime =
        gameSessionControlPlaneClient.getGameInstanceRuntimeState(
            tenantId, gameInstanceId, preferredRegionId);
    if (runtime == null
        || (runtime.hasError() && !runtime.getError().getCode().isBlank())
        || !runtime.hasRuntimeState()) {
      return RuntimeScope.UNKNOWN;
    }
    return RuntimeScope.from(
        normalize(runtime.getRuntimeState().getRegionId()),
        runtime.getRuntimeState().getRegionEpoch());
  }

  static boolean matches(
      PluginRuntimeState state, String runtimeRegionId, long runtimeRegionEpoch) {
    return matches(state, RuntimeScope.from(normalize(runtimeRegionId), runtimeRegionEpoch));
  }

  static boolean matches(PluginRuntimeState state, RuntimeScope runtimeScope) {
    if (!runtimeScope.known()) {
      return true;
    }
    String stateRegionId = normalize(state.getRuntimeRegionId());
    long stateRegionEpoch = zeroIfNull(state.getRuntimeRegionEpoch());
    if (stateRegionId.isBlank() || stateRegionEpoch <= 0) {
      return false;
    }
    return stateRegionId.equals(runtimeScope.regionId())
        && stateRegionEpoch == runtimeScope.regionEpoch();
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }

  private static long zeroIfNull(Long value) {
    return value == null ? 0L : value;
  }

  record RuntimeScope(String regionId, long regionEpoch) {
    static final RuntimeScope UNKNOWN = new RuntimeScope("", 0L);

    private static RuntimeScope from(String regionId, long regionEpoch) {
      if (regionId.isBlank() || regionEpoch <= 0) {
        return UNKNOWN;
      }
      return new RuntimeScope(regionId, regionEpoch);
    }

    private boolean known() {
      return !regionId.isBlank() && regionEpoch > 0;
    }
  }
}
