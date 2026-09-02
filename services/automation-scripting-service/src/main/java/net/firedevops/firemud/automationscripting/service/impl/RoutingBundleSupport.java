package net.firedevops.firemud.automationscripting.service.impl;

import java.util.Locale;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
    long parsedPointerVersion =
        RequestIdValidation.requirePositiveLong(pointerVersion, "pointerVersion");
    return new RoutingBundle(worldSlug, realmSlug, pointerVersion, parsedPointerVersion);
  }

  static RoutingBundle normalize(String worldSlug, String realmSlug, long pointerVersion) {
    if (!StringUtils.hasText(worldSlug) || !StringUtils.hasText(realmSlug) || pointerVersion <= 0) {
      return RoutingBundle.EMPTY;
    }
    return new RoutingBundle(worldSlug, realmSlug, Long.toString(pointerVersion), pointerVersion);
  }

  static RoutingBundle fromRuntimeState(GameInstanceRuntimeState runtimeState) {
    if (runtimeState == null) {
      return RoutingBundle.EMPTY;
    }
    if (runtimeState.getCurrentAdmissionPointersCount() != 1) {
      return RoutingBundle.EMPTY;
    }
    var pointer = runtimeState.getCurrentAdmissionPointers(0);
    if (!positiveLong(pointer.getTenantId())
        || !StringUtils.hasText(pointer.getGameInstanceId())
        || pointer.getPointerVersion() <= 0
        || !StringUtils.hasText(pointer.getStateScope())
        || !pointer.getTenantId().equals(runtimeState.getTenantId())
        || !pointer.getGameInstanceId().equals(runtimeState.getGameInstanceId())
        || !samePlayableStateScope(pointer.getStateScope(), runtimeState.getPlayableStateScope())) {
      return RoutingBundle.EMPTY;
    }
    return normalize(pointer.getWorldSlug(), pointer.getRealmSlug(), pointer.getPointerVersion());
  }

  private static boolean samePlayableStateScope(
      String pointerScope, PlayableStateScope runtimeScope) {
    if (runtimeScope == null) {
      return false;
    }
    String normalizedPointerScope =
        pointerScope == null ? "" : pointerScope.trim().toUpperCase(Locale.ROOT);
    String normalizedRuntimeScope =
        switch (runtimeScope) {
          case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
          case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
          case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> "";
        };
    return !normalizedRuntimeScope.isBlank()
        && normalizedRuntimeScope.equals(normalizedPointerScope);
  }

  static boolean hasPartialRouting(String worldSlug, String realmSlug, String pointerVersion) {
    boolean hasAny =
        StringUtils.hasText(worldSlug)
            || StringUtils.hasText(realmSlug)
            || StringUtils.hasText(pointerVersion);
    boolean hasAll =
        StringUtils.hasText(worldSlug)
            && StringUtils.hasText(realmSlug)
            && StringUtils.hasText(pointerVersion);
    return hasAny && !hasAll;
  }

  static boolean sameRoutingBundle(RoutingBundle left, RoutingBundle right) {
    return left != null
        && right != null
        && left.isPresent()
        && right.isPresent()
        && left.worldSlug().equalsIgnoreCase(right.worldSlug())
        && left.realmSlug().equalsIgnoreCase(right.realmSlug())
        && left.parsedPointerVersion() == right.parsedPointerVersion();
  }

  record RoutingBundle(
      String worldSlug, String realmSlug, String pointerVersion, long parsedPointerVersion) {
    private static final RoutingBundle EMPTY = new RoutingBundle("", "", "", 0L);

    boolean isPresent() {
      return !worldSlug.isBlank();
    }
  }

  private static boolean positiveLong(String value) {
    try {
      return StringUtils.hasText(value) && RequestIdValidation.requirePositiveLong(value, "id") > 0;
    } catch (RuntimeException ex) {
      return false;
    }
  }
}
