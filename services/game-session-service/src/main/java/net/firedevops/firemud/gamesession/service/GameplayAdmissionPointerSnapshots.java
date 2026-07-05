package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Optional;
import org.springframework.util.StringUtils;

public final class GameplayAdmissionPointerSnapshots {
  private GameplayAdmissionPointerSnapshots() {}

  public record AdmittedRoutingBundle(String worldSlug, String realmSlug, String pointerVersion) {
    public boolean isPresent() {
      return worldSlug != null && realmSlug != null && pointerVersion != null;
    }
  }

  public record RoutingBundle(String worldSlug, String realmSlug, Long pointerVersion) {}

  public static AdmittedRoutingBundle admittedRoutingBundle(SessionContext context) {
    if (context == null) {
      return new AdmittedRoutingBundle(null, null, null);
    }
    return admittedRoutingBundle(
        context.worldSlug(), context.realmSlug(), context.pointerVersion());
  }

  public static boolean hasPartialAdmittedRoutingBundle(SessionContext context) {
    if (context == null) {
      return false;
    }
    AdmittedRoutingBundle routingBundle =
        admittedRoutingBundle(context.worldSlug(), context.realmSlug(), context.pointerVersion());
    return (!routingBundle.isPresent())
        && (StringUtils.hasText(context.worldSlug())
            || StringUtils.hasText(context.realmSlug())
            || context.pointerVersion() > 0);
  }

  public static boolean hasCompleteRoutingBundle(GameplayAdmissionPointerSnapshot pointer) {
    return pointer != null
        && pointer.tenantId() > 0L
        && pointer.gameInstanceId() > 0L
        && pointer.pointerVersion() > 0L
        && pointer.worldSlug() != null
        && !pointer.worldSlug().isBlank()
        && pointer.realmSlug() != null
        && !pointer.realmSlug().isBlank()
        && pointer.stateScope() != null
        && !pointer.stateScope().isBlank();
  }

  public static boolean hasCompleteRoutingBundle(SessionContext shell) {
    return shell != null
        && shell.pointerVersion() > 0L
        && StringUtils.hasText(shell.worldSlug())
        && StringUtils.hasText(shell.realmSlug());
  }

  public static boolean sameBootstrapRoute(SessionContext existing, SessionContext incoming) {
    if (existing == null || incoming == null) {
      return false;
    }
    if (existing.tenantId() != incoming.tenantId()) {
      return false;
    }
    if (existing.bootstrapGameInstanceId() != incoming.bootstrapGameInstanceId()) {
      return false;
    }
    boolean existingHasBundle = hasCompleteRoutingBundle(existing);
    boolean incomingHasBundle = hasCompleteRoutingBundle(incoming);
    if (existingHasBundle != incomingHasBundle) {
      return false;
    }
    if (!incomingHasBundle) {
      return true;
    }
    if (!incoming.worldSlug().equalsIgnoreCase(existing.worldSlug())) {
      return false;
    }
    if (!incoming.realmSlug().equalsIgnoreCase(existing.realmSlug())) {
      return false;
    }
    return existing.pointerVersion() == incoming.pointerVersion();
  }

  public static Optional<GameplayAdmissionPointerSnapshot> singularCompletePointer(
      List<GameplayAdmissionPointerSnapshot> pointers) {
    if (pointers == null) {
      return Optional.empty();
    }
    List<GameplayAdmissionPointerSnapshot> runtimePointers =
        pointers.stream().filter(pointer -> pointer != null).toList();
    if (runtimePointers.size() != 1) {
      return Optional.empty();
    }
    GameplayAdmissionPointerSnapshot pointer = runtimePointers.getFirst();
    return hasCompleteRoutingBundle(pointer) ? Optional.of(pointer) : Optional.empty();
  }

  public static boolean matchesCurrentRuntimeTarget(
      List<GameplayAdmissionPointerSnapshot> pointers,
      long tenantId,
      long gameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {
    return matchesCurrentRuntimeTarget(
        pointers, tenantId, gameInstanceId, worldSlug, realmSlug, pointerVersion, null);
  }

  public static boolean matchesCurrentRuntimeTarget(
      List<GameplayAdmissionPointerSnapshot> pointers,
      long tenantId,
      long gameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      String playableStateScope) {
    if (tenantId <= 0 || gameInstanceId <= 0 || pointerVersion <= 0) {
      return false;
    }
    if (!StringUtils.hasText(worldSlug) || !StringUtils.hasText(realmSlug)) {
      return false;
    }
    String normalizedPlayableStateScope =
        StringUtils.hasText(playableStateScope) ? playableStateScope : null;
    return singularCompletePointer(pointers)
        .filter(pointer -> pointer.tenantId() == tenantId)
        .filter(pointer -> pointer.gameInstanceId() == gameInstanceId)
        .filter(pointer -> pointer.worldSlug().equals(worldSlug))
        .filter(pointer -> pointer.realmSlug().equals(realmSlug))
        .filter(pointer -> pointer.pointerVersion() == pointerVersion)
        .filter(
            pointer ->
                normalizedPlayableStateScope == null
                    || normalizedPlayableStateScope.equals(blankToNull(pointer.stateScope())))
        .isPresent();
  }

  public static RoutingBundle normalizeRoutingBundle(
      String worldSlug, String realmSlug, Long pointerVersion) {
    String normalizedWorldSlug = blankToNull(worldSlug);
    String normalizedRealmSlug = blankToNull(realmSlug);
    Long normalizedPointerVersion =
        pointerVersion != null && pointerVersion > 0L ? pointerVersion : null;
    boolean hasAny =
        normalizedWorldSlug != null
            || normalizedRealmSlug != null
            || normalizedPointerVersion != null;
    boolean hasAll =
        normalizedWorldSlug != null
            && normalizedRealmSlug != null
            && normalizedPointerVersion != null;
    if (!hasAny || !hasAll) {
      return null;
    }
    return new RoutingBundle(normalizedWorldSlug, normalizedRealmSlug, normalizedPointerVersion);
  }

  public static void requireCompleteOrAbsentRoutingBundle(
      String worldSlug, String realmSlug, Long pointerVersion, String message) {
    if (normalizeRoutingBundle(worldSlug, realmSlug, pointerVersion) == null
        && hasAnyRoutingValue(worldSlug, realmSlug, pointerVersion)) {
      throw new IllegalArgumentException(message);
    }
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

  private static boolean hasAnyRoutingValue(
      String worldSlug, String realmSlug, Long pointerVersion) {
    return StringUtils.hasText(worldSlug)
        || StringUtils.hasText(realmSlug)
        || (pointerVersion != null && pointerVersion > 0L);
  }

  private static AdmittedRoutingBundle admittedRoutingBundle(
      String worldSlug, String realmSlug, long pointerVersion) {
    String normalizedWorldSlug = StringUtils.hasText(worldSlug) ? worldSlug : null;
    String normalizedRealmSlug = StringUtils.hasText(realmSlug) ? realmSlug : null;
    String normalizedPointerVersion = pointerVersion > 0L ? Long.toString(pointerVersion) : null;
    boolean hasAny =
        normalizedWorldSlug != null
            || normalizedRealmSlug != null
            || normalizedPointerVersion != null;
    boolean hasAll =
        normalizedWorldSlug != null
            && normalizedRealmSlug != null
            && normalizedPointerVersion != null;
    if (!hasAny || !hasAll) {
      return new AdmittedRoutingBundle(null, null, null);
    }
    return new AdmittedRoutingBundle(
        normalizedWorldSlug, normalizedRealmSlug, normalizedPointerVersion);
  }
}
