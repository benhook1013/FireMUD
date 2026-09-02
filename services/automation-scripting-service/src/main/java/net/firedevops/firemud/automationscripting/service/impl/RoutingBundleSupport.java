package net.firedevops.firemud.automationscripting.service.impl;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.GameInstanceRuntimeState;
import org.springframework.util.StringUtils;

final class RoutingBundleSupport {
  private static final int MAX_SLUG_BYTES = 120;
  private static final Pattern CANONICAL_SLUG_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");

  private RoutingBundleSupport() {}

  static RoutingBundle normalize(String worldSlug, String realmSlug, String pointerVersion) {
    if (!StringUtils.hasText(worldSlug)
        || !StringUtils.hasText(realmSlug)
        || !StringUtils.hasText(pointerVersion)) {
      return RoutingBundle.EMPTY;
    }
    final long parsedPointerVersion;
    try {
      parsedPointerVersion =
          RequestIdValidation.requirePositiveLong(pointerVersion, "pointerVersion");
    } catch (RuntimeException ex) {
      return RoutingBundle.EMPTY;
    }
    String normalizedWorldSlug = normalizeSlug(worldSlug);
    String normalizedRealmSlug = normalizeSlug(realmSlug);
    if (normalizedWorldSlug.isBlank() || normalizedRealmSlug.isBlank()) {
      return RoutingBundle.EMPTY;
    }
    return new RoutingBundle(
        normalizedWorldSlug, normalizedRealmSlug, pointerVersion, parsedPointerVersion);
  }

  static RoutingBundle normalize(String worldSlug, String realmSlug, long pointerVersion) {
    if (!StringUtils.hasText(worldSlug) || !StringUtils.hasText(realmSlug) || pointerVersion <= 0) {
      return RoutingBundle.EMPTY;
    }
    String normalizedWorldSlug = normalizeSlug(worldSlug);
    String normalizedRealmSlug = normalizeSlug(realmSlug);
    if (normalizedWorldSlug.isBlank() || normalizedRealmSlug.isBlank()) {
      return RoutingBundle.EMPTY;
    }
    return new RoutingBundle(
        normalizedWorldSlug, normalizedRealmSlug, Long.toString(pointerVersion), pointerVersion);
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
        || canonicalPlayableStateScope(pointer.getStateScope()).isBlank()
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
    String normalizedPointerScope = canonicalPlayableStateScope(pointerScope);
    String normalizedRuntimeScope =
        switch (runtimeScope) {
          case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
          case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
          case PLAYABLE_STATE_SCOPE_UNSPECIFIED, UNRECOGNIZED -> "";
        };
    return !normalizedRuntimeScope.isBlank()
        && normalizedRuntimeScope.equals(normalizedPointerScope);
  }

  private static String canonicalPlayableStateScope(String stateScope) {
    String normalized = stateScope == null ? "" : stateScope.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "SHARED", "PLAYABLE_STATE_SCOPE_SHARED" -> "SHARED";
      case "ISOLATED", "PLAYABLE_STATE_SCOPE_ISOLATED" -> "ISOLATED";
      default -> "";
    };
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
        && left.worldSlug().equals(right.worldSlug())
        && left.realmSlug().equals(right.realmSlug())
        && left.parsedPointerVersion() == right.parsedPointerVersion();
  }

  private static String normalizeSlug(String slug) {
    if (slug.chars().anyMatch(character -> character > 0x7F)) {
      return "";
    }
    String normalized = slug.toLowerCase(Locale.ROOT);
    return normalized.getBytes(StandardCharsets.UTF_8).length <= MAX_SLUG_BYTES
            && CANONICAL_SLUG_PATTERN.matcher(normalized).matches()
        ? normalized
        : "";
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
