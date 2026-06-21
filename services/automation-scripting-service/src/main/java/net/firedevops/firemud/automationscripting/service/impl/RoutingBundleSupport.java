package net.firedevops.firemud.automationscripting.service.impl;

import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.springframework.util.StringUtils;

final class RoutingBundleSupport {
  private RoutingBundleSupport() {}

  static RoutingBundle normalize(String worldSlug, String realmSlug, String pointerVersion) {
    if (!StringUtils.hasText(worldSlug)
        || !StringUtils.hasText(realmSlug)
        || !StringUtils.hasText(pointerVersion)) {
      return RoutingBundle.EMPTY;
    }
    return new RoutingBundle(worldSlug, realmSlug, pointerVersion);
  }

  static RoutingBundle normalize(String worldSlug, String realmSlug, long pointerVersion) {
    if (pointerVersion <= 0) {
      return RoutingBundle.EMPTY;
    }
    return normalize(worldSlug, realmSlug, Long.toString(pointerVersion));
  }

  static RoutingBundle fromRuntimeState(GameInstanceRuntimeState runtimeState) {
    if (runtimeState == null) {
      return RoutingBundle.EMPTY;
    }
    if (runtimeState.getCurrentAdmissionPointersCount() != 1) {
      return RoutingBundle.EMPTY;
    }
    var pointer = runtimeState.getCurrentAdmissionPointers(0);
    return normalize(pointer.getWorldSlug(), pointer.getRealmSlug(), pointer.getPointerVersion());
  }

  record RoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {
    private static final RoutingBundle EMPTY = new RoutingBundle("", "", "");

    boolean isPresent() {
      return !worldSlug.isBlank();
    }

    long parsedPointerVersion() {
      return pointerVersion.isBlank() ? 0L : Long.parseLong(pointerVersion);
    }
  }
}
