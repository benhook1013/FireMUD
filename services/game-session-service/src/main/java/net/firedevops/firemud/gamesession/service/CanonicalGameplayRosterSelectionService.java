package net.firedevops.firemud.gamesession.service;

import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.command.text.CanonicalGameplayActorSelection;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Non-admitting composition of the current published route and Entity's preseeded roster reads. The
 * execution context must already have been constructed from authenticated Game Session state; this
 * unsigned scope carrier is not authentication, Account eligibility, or gameplay authority.
 */
public final class CanonicalGameplayRosterSelectionService {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern SNAPSHOT_DIGEST = Pattern.compile("[0-9a-f]{64}");

  private final CanonicalPlayerRouteReadService routeReader;
  private final CanonicalPublishedPlayerRouteReadService publishedRouteReader;
  private final CanonicalGameplayRosterClient rosterClient;

  public CanonicalGameplayRosterSelectionService(
      CanonicalPlayerRouteReadService routeReader,
      CanonicalPublishedPlayerRouteReadService publishedRouteReader,
      CanonicalGameplayRosterClient rosterClient) {
    this.routeReader = Objects.requireNonNull(routeReader, "routeReader");
    this.publishedRouteReader =
        Objects.requireNonNull(publishedRouteReader, "publishedRouteReader");
    this.rosterClient = Objects.requireNonNull(rosterClient, "rosterClient");
  }

  /**
   * Reads a route-bound roster, accepts only an optional one-based ordinal, and obtains Entity's
   * original selected-assignment reference. An explicit ordinal must carry the identity returned
   * with its menu so a refreshed roster cannot silently rebind that choice. No result from this
   * method authorizes or activates gameplay.
   */
  public Result select(
      PlayerExecutionContext authenticatedContext,
      String selector,
      SelectionMenuIdentity expectedMenuIdentity) {
    if (hasAmbientTransaction()) {
      return new Denied(Denial.AMBIENT_TRANSACTION);
    }
    Optional<UUID> accountUuid = validateContext(authenticatedContext);
    if (accountUuid.isEmpty()) {
      return new Denied(Denial.INVALID_CONTEXT);
    }

    CanonicalPublishedPlayerRoute current;
    try {
      CanonicalPlayableTarget selectedTarget =
          routeReader.readCurrentTarget(parseCanonicalUuid(authenticatedContext.getTenantId()));
      if (selectedTarget == null || !matchesContext(authenticatedContext, selectedTarget)) {
        return new Denied(Denial.ROUTE_MISMATCH);
      }
      current = publishedRouteReader.readCurrent(selectedTarget);
      if (current == null || !matchesContext(authenticatedContext, current.route())) {
        return new Denied(Denial.ROUTE_MISMATCH);
      }
    } catch (RuntimeException unavailableOrInvalid) {
      return new Denied(routeFailure(unavailableOrInvalid));
    }

    CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot;
    try {
      snapshot = rosterClient.listPreseededRoster(current, authenticatedContext);
    } catch (StatusRuntimeException unavailable) {
      return new Denied(Denial.ROSTER_UNAVAILABLE);
    } catch (RuntimeException invalidOrUnavailable) {
      return new Denied(Denial.INVALID_ROSTER_PROOF);
    }
    if (snapshot == null) {
      return new Denied(Denial.INVALID_ROSTER_PROOF);
    }

    Denial listFreshnessFailure = requireExactCurrentRoute(current);
    if (listFreshnessFailure != null) {
      return new Denied(listFreshnessFailure);
    }

    Optional<CanonicalGameplayActorSelection.ValidatedRoster> validated =
        CanonicalGameplayActorSelection.validate(snapshot, accountUuid.orElseThrow(), current);
    if (validated.isEmpty()) {
      return new Denied(Denial.INVALID_ROSTER_PROOF);
    }

    CanonicalGameplayActorSelection.SelectionResult selection =
        CanonicalGameplayActorSelection.select(validated.orElseThrow(), selector);
    if (selection instanceof CanonicalGameplayActorSelection.SelectionRequired required) {
      return new SelectionRequired(
          required.choices(),
          new SelectionMenuIdentity(snapshot.snapshotUuid(), snapshot.snapshotDigest()));
    }
    if (selection instanceof CanonicalGameplayActorSelection.Denied denied) {
      return new Denied(mapSelectionDenial(denied.reason()));
    }

    if (selector != null && !selector.isBlank()) {
      if (expectedMenuIdentity == null) {
        return new Denied(Denial.MISSING_MENU_IDENTITY);
      }
      if (!expectedMenuIdentity.matches(snapshot)) {
        return new Denied(Denial.STALE_MENU_IDENTITY);
      }
    }

    CanonicalGameplayActorSelection.Selected selected =
        (CanonicalGameplayActorSelection.Selected) selection;
    PlayerExecutionContext selectedContext =
        authenticatedContext.toBuilder()
            .setRequestId(UUID.randomUUID().toString())
            .setCharacterId(selected.characterUuid().toString())
            .build();

    CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference;
    try {
      ownerReference = rosterClient.readSelectedPreseededAssignment(snapshot, selectedContext);
    } catch (StatusRuntimeException unavailable) {
      return new Denied(Denial.ASSIGNMENT_UNAVAILABLE);
    } catch (RuntimeException invalidOrUnavailable) {
      return new Denied(Denial.INVALID_ASSIGNMENT_PROOF);
    }
    if (!matchesOwnerReference(ownerReference, selected)) {
      return new Denied(Denial.INVALID_ASSIGNMENT_PROOF);
    }

    Denial assignmentFreshnessFailure = requireExactCurrentRoute(current);
    if (assignmentFreshnessFailure != null) {
      return new Denied(assignmentFreshnessFailure);
    }
    return new SelectedOwnerReference(ownerReference, selected.displayName(), selected.ordinal());
  }

  private Denial requireExactCurrentRoute(CanonicalPublishedPlayerRoute expected) {
    try {
      CanonicalPublishedPlayerRoute current = publishedRouteReader.readCurrent(expected.route());
      return expected.equals(current) ? null : Denial.ROUTE_CHANGED;
    } catch (RuntimeException unavailableOrInvalid) {
      return routeFailure(unavailableOrInvalid);
    }
  }

  private static Denial routeFailure(RuntimeException failure) {
    if (failure instanceof CanonicalPublishedPlayerRouteReadService.StaleAuthorityException) {
      return Denial.ROUTE_CHANGED;
    }
    if (failure instanceof CanonicalPlayerRouteReadService.ReadUnavailableException
        || failure instanceof CanonicalPublishedPlayerRouteReadService.StorageUnavailableException
        || failure
            instanceof CanonicalPublishedPlayerRouteReadService.GameDesignUnavailableException
        || failure instanceof StatusRuntimeException) {
      return Denial.ROUTE_UNAVAILABLE;
    }
    return Denial.INVALID_ROUTE_PROOF;
  }

  private static Optional<UUID> validateContext(PlayerExecutionContext context) {
    if (context == null || !context.getUnknownFields().asMap().isEmpty()) {
      return Optional.empty();
    }
    try {
      UUID accountUuid = parseCanonicalUuid(context.getAccountId());
      parseCanonicalUuid(context.getSessionId());
      parseCanonicalUuid(context.getRequestId());
      parseCanonicalUuid(context.getTenantId());
      parseCanonicalUuid(context.getRealmId());
      parseCanonicalUuid(context.getPlayableStateNamespaceId());
      parseCanonicalUuid(context.getGameInstanceId());
      if ((!"SHARED".equals(context.getPlayableStateScope())
              && !"ISOLATED".equals(context.getPlayableStateScope()))
          || !context.getCharacterId().isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(accountUuid);
    } catch (IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }

  private static UUID parseCanonicalUuid(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Canonical UUID is required");
    }
    UUID parsed = UUID.fromString(value);
    if (parsed.equals(NIL_UUID) || !parsed.toString().equals(value)) {
      throw new IllegalArgumentException("Canonical UUID is malformed");
    }
    return parsed;
  }

  private static boolean matchesContext(
      PlayerExecutionContext context, CanonicalPlayableTarget route) {
    return context.getTenantId().equals(route.canonicalTenantId().toString())
        && context.getRealmId().equals(route.realmId().toString())
        && context.getPlayableStateNamespaceId().equals(route.playableStateNamespaceId().toString())
        && context.getPlayableStateScope().equals(route.playableStateScope())
        && context.getGameInstanceId().equals(route.canonicalGameInstanceId().toString());
  }

  private static boolean matchesOwnerReference(
      CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference,
      CanonicalGameplayActorSelection.Selected selected) {
    return ownerReference != null
        && ownerReference.canonicalAccountUuid().equals(selected.canonicalAccountUuid())
        && ownerReference.selectedCharacterUuid().equals(selected.characterUuid())
        && ownerReference.target().equals(selected.target())
        && ownerReference.rosterSnapshotUuid().equals(selected.rosterSnapshotUuid())
        && ownerReference.rosterSnapshotDigest().equals(selected.rosterSnapshotDigest());
  }

  private static Denial mapSelectionDenial(CanonicalGameplayActorSelection.Denial reason) {
    return switch (reason) {
      case INVALID_SNAPSHOT -> Denial.INVALID_ROSTER_PROOF;
      case NO_PRESEEDED_ACTOR -> Denial.NO_PRESEEDED_ACTOR;
      case INVALID_SELECTOR -> Denial.INVALID_SELECTOR;
      case SELECTOR_OUT_OF_RANGE -> Denial.SELECTOR_OUT_OF_RANGE;
    };
  }

  private static boolean hasAmbientTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive();
  }

  public sealed interface Result permits SelectedOwnerReference, SelectionRequired, Denied {}

  /**
   * Entity's original committed owner reference, still only source evidence for later revalidation.
   */
  public record SelectedOwnerReference(
      CanonicalGameplayRosterClient.VerifiedPreseededAssignment ownerReference,
      String displayName,
      int ordinal)
      implements Result {
    public SelectedOwnerReference {
      Objects.requireNonNull(ownerReference, "ownerReference");
      Objects.requireNonNull(displayName, "displayName");
      if (ordinal < 1) {
        throw new IllegalArgumentException("ordinal must be one-based");
      }
    }
  }

  public record SelectionMenuIdentity(UUID snapshotUuid, String snapshotDigest) {
    public SelectionMenuIdentity {
      Objects.requireNonNull(snapshotUuid, "snapshotUuid");
      Objects.requireNonNull(snapshotDigest, "snapshotDigest");
      if (NIL_UUID.equals(snapshotUuid) || !SNAPSHOT_DIGEST.matcher(snapshotDigest).matches()) {
        throw new IllegalArgumentException("Selection menu identity is malformed");
      }
    }

    private boolean matches(CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot) {
      return snapshotUuid.equals(snapshot.snapshotUuid())
          && snapshotDigest.equals(snapshot.snapshotDigest());
    }
  }

  public record SelectionRequired(
      List<CanonicalGameplayActorSelection.Choice> choices, SelectionMenuIdentity menuIdentity)
      implements Result {
    public SelectionRequired {
      choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
      Objects.requireNonNull(menuIdentity, "menuIdentity");
    }
  }

  public record Denied(Denial reason) implements Result {
    public Denied {
      Objects.requireNonNull(reason, "reason");
    }
  }

  public enum Denial {
    AMBIENT_TRANSACTION,
    INVALID_CONTEXT,
    ROUTE_UNAVAILABLE,
    INVALID_ROUTE_PROOF,
    ROUTE_MISMATCH,
    ROUTE_CHANGED,
    ROSTER_UNAVAILABLE,
    INVALID_ROSTER_PROOF,
    NO_PRESEEDED_ACTOR,
    INVALID_SELECTOR,
    SELECTOR_OUT_OF_RANGE,
    MISSING_MENU_IDENTITY,
    STALE_MENU_IDENTITY,
    ASSIGNMENT_UNAVAILABLE,
    INVALID_ASSIGNMENT_PROOF
  }
}
