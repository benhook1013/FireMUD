package net.firedevops.firemud.gamesession.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

  public static AdmittedRoutingBundle requireAdmittedRoutingBundle(
      SessionContext context, String requestTarget) {
    AdmittedRoutingBundle routingBundle = admittedRoutingBundle(context);
    if (hasPartialAdmittedRoutingBundle(context)) {
      throw new IllegalStateException(
          "Incomplete admitted routing bundle on session context for " + requestTarget);
    }
    if (!routingBundle.isPresent()) {
      throw new IllegalStateException(
          "Missing admitted routing bundle on session context for " + requestTarget);
    }
    return routingBundle;
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

  public static SessionContext repairGenericBootstrapShell(
      SessionContext shell, List<GameplayAdmissionPointerSnapshot> runtimePointers) {
    if (shell == null) {
      return null;
    }
    List<GameplayAdmissionPointerSnapshot> filteredRuntimePointers =
        runtimePointers == null
            ? List.of()
            : runtimePointers.stream().filter(pointer -> pointer != null).toList();
    SessionContext normalizedShell = normalizePartialGenericRouting(shell);
    if (hasCompleteRoutingBundle(normalizedShell)) {
      if (filteredRuntimePointers.isEmpty()) {
        return normalizedShell;
      }
      if (matchesCurrentRuntimeTarget(
          filteredRuntimePointers,
          normalizedShell.tenantId(),
          normalizedShell.bootstrapGameInstanceId(),
          normalizedShell.worldSlug(),
          normalizedShell.realmSlug(),
          normalizedShell.pointerVersion())) {
        return normalizedShell;
      }
      return clearGenericBootstrapRouting(normalizedShell);
    }
    return singularCompletePointer(filteredRuntimePointers)
        .map(
            pointer ->
                new SessionContext(
                    normalizedShell.sessionId(),
                    normalizedShell.tenantId(),
                    normalizedShell.accountId(),
                    normalizedShell.loginName(),
                    normalizedShell.characterId(),
                    normalizedShell.characterName(),
                    normalizedShell.gameInstanceId(),
                    normalizedShell.roomInstanceId(),
                    normalizedShell.jwt(),
                    normalizedShell.localeTag(),
                    normalizedShell.bootstrapGameInstanceId(),
                    pointer.worldSlug(),
                    pointer.realmSlug(),
                    pointer.pointerVersion(),
                    pointer.stateScope(),
                    normalizedShell.connectScopeId(),
                    normalizedShell.connectRequestId()))
        .orElse(clearGenericBootstrapRouting(normalizedShell));
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
        && StringUtils.hasText(shell.realmSlug())
        && StringUtils.hasText(shell.playableStateScope());
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
      return !hasAnyRoutingValue(existing) && !hasAnyRoutingValue(incoming);
    }
    if (!sameRoutingIdentity(
        existing.worldSlug(), existing.realmSlug(), incoming.worldSlug(), incoming.realmSlug())) {
      return false;
    }
    return existing.pointerVersion() == incoming.pointerVersion()
        && Objects.equals(
            normalizeScope(existing.playableStateScope()),
            normalizeScope(incoming.playableStateScope()));
  }

  public static boolean sameBootstrapRoute(
      FirstPartyConnectContext existing,
      long tenantId,
      long bootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {
    if (existing == null) {
      return false;
    }
    if (existing.tenantId() != tenantId || existing.gameInstanceId() != bootstrapGameInstanceId) {
      return false;
    }
    RoutingBundle existingRoutingBundle =
        normalizeRoutingBundle(
            existing.worldSlug(), existing.realmSlug(), Long.valueOf(existing.pointerVersion()));
    RoutingBundle incomingRoutingBundle =
        normalizeRoutingBundle(worldSlug, realmSlug, Long.valueOf(pointerVersion));
    if ((existingRoutingBundle == null) != (incomingRoutingBundle == null)) {
      return false;
    }
    if (incomingRoutingBundle == null) {
      return true;
    }
    if (!sameRoutingIdentity(
        existingRoutingBundle.worldSlug(),
        existingRoutingBundle.realmSlug(),
        incomingRoutingBundle.worldSlug(),
        incomingRoutingBundle.realmSlug())) {
      return false;
    }
    return existingRoutingBundle.pointerVersion().equals(incomingRoutingBundle.pointerVersion());
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
    return singularCompletePointer(pointers)
        .map(
            pointer ->
                matchesCurrentRuntimeTarget(
                    List.of(pointer),
                    tenantId,
                    gameInstanceId,
                    worldSlug,
                    realmSlug,
                    pointerVersion,
                    pointer.stateScope()))
        .orElse(false);
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
    if (!StringUtils.hasText(playableStateScope)) {
      return false;
    }
    String normalizedPlayableStateScope = normalizeScope(playableStateScope);
    return singularCompletePointer(pointers)
        .filter(pointer -> pointer.tenantId() == tenantId)
        .filter(pointer -> pointer.gameInstanceId() == gameInstanceId)
        .filter(
            pointer ->
                sameRoutingIdentity(pointer.worldSlug(), pointer.realmSlug(), worldSlug, realmSlug))
        .filter(pointer -> pointer.pointerVersion() == pointerVersion)
        .filter(
            pointer -> normalizedPlayableStateScope.equals(normalizeScope(pointer.stateScope())))
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

  public static void requireCompleteOrAbsentRoutingBundle(
      String worldSlug,
      String realmSlug,
      Long pointerVersion,
      String playableStateScope,
      String message) {
    RoutingBundle routingBundle = normalizeRoutingBundle(worldSlug, realmSlug, pointerVersion);
    if (routingBundle == null) {
      if (hasAnyRoutingValue(worldSlug, realmSlug, pointerVersion)
          || StringUtils.hasText(playableStateScope)) {
        throw new IllegalArgumentException(message);
      }
      return;
    }
    if (!StringUtils.hasText(playableStateScope)) {
      throw new IllegalArgumentException(message);
    }
  }

  private static SessionContext clearGenericBootstrapRouting(SessionContext shell) {
    return new SessionContext(
        shell.sessionId(),
        shell.tenantId(),
        shell.accountId(),
        shell.loginName(),
        shell.characterId(),
        shell.characterName(),
        shell.gameInstanceId(),
        shell.roomInstanceId(),
        shell.jwt(),
        shell.localeTag(),
        shell.bootstrapGameInstanceId(),
        null,
        null,
        0L,
        shell.playableStateScope(),
        shell.connectScopeId(),
        shell.connectRequestId());
  }

  private static SessionContext normalizePartialGenericRouting(SessionContext shell) {
    if (!hasPartialAdmittedRoutingBundle(shell)) {
      return shell;
    }
    return clearGenericBootstrapRouting(shell);
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

  private static String normalizeScope(String value) {
    return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
  }

  private static boolean sameRoutingIdentity(
      String existingWorldSlug,
      String existingRealmSlug,
      String incomingWorldSlug,
      String incomingRealmSlug) {
    return existingWorldSlug.equalsIgnoreCase(incomingWorldSlug)
        && existingRealmSlug.equalsIgnoreCase(incomingRealmSlug);
  }

  private static boolean hasAnyRoutingValue(
      String worldSlug, String realmSlug, Long pointerVersion) {
    return StringUtils.hasText(worldSlug)
        || StringUtils.hasText(realmSlug)
        || (pointerVersion != null && pointerVersion > 0L);
  }

  private static boolean hasAnyRoutingValue(SessionContext shell) {
    return shell != null
        && (hasAnyRoutingValue(shell.worldSlug(), shell.realmSlug(), shell.pointerVersion())
            || StringUtils.hasText(shell.playableStateScope()));
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
