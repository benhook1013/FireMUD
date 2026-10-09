package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.CanonicalGameplayRosterClient;
import net.firedevops.firemud.gamesession.dto.CanonicalPublishedPlayerRoute;

/**
 * Validates and selects actors only from one Account- and route-bound canonical roster snapshot.
 */
public final class CanonicalGameplayActorSelection {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final int MAX_ROSTER_SIZE = 100;

  private CanonicalGameplayActorSelection() {}

  /**
   * Returns an opaque validated view only when the complete Entity snapshot matches the trusted
   * Account and the exact current published route. A rejected snapshot must not reach PLAY.
   */
  public static Optional<ValidatedRoster> validate(
      CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot,
      UUID expectedAccountUuid,
      CanonicalPublishedPlayerRoute expectedRoute) {
    try {
      if (snapshot == null
          || !isNonNil(expectedAccountUuid)
          || !expectedAccountUuid.equals(snapshot.canonicalAccountUuid())
          || !isNonNil(snapshot.snapshotUuid())
          || !isCanonicalDigest(snapshot.snapshotDigest())
          || expectedRoute == null
          || !matchesExactPublishedTarget(snapshot.target(), expectedRoute)) {
        return Optional.empty();
      }
      List<CanonicalGameplayRosterClient.RosterActor> actors = snapshot.actors();
      if (actors == null || actors.size() > MAX_ROSTER_SIZE) {
        return Optional.empty();
      }
      Set<UUID> actorIds = new HashSet<>();
      for (CanonicalGameplayRosterClient.RosterActor actor : actors) {
        if (actor == null
            || !isNonNil(actor.characterUuid())
            || !actorIds.add(actor.characterUuid())
            || !isCanonicalDisplayName(actor.displayName())) {
          return Optional.empty();
        }
      }
      return Optional.of(new ValidatedRoster(snapshot, expectedAccountUuid, expectedRoute));
    } catch (RuntimeException malformed) {
      return Optional.empty();
    }
  }

  /**
   * Resolves an optional one-based menu ordinal. Actor UUIDs and actor names are never accepted as
   * user selectors; the selected UUID remains internal output from this exact validated snapshot.
   */
  public static SelectionResult select(ValidatedRoster roster, String selector) {
    if (roster == null) {
      return new Denied(Denial.INVALID_SNAPSHOT);
    }
    List<CanonicalGameplayRosterClient.RosterActor> actors = roster.snapshot().actors();
    if (actors.isEmpty()) {
      return new Denied(Denial.NO_PRESEEDED_ACTOR);
    }
    if (selector == null || selector.isBlank()) {
      if (actors.size() == 1) {
        return selected(roster, 1, actors.getFirst());
      }
      return new SelectionRequired(
          actors.stream()
              .map(actor -> new Choice(actors.indexOf(actor) + 1, actor.displayName()))
              .toList());
    }
    if (!selector.matches("[1-9][0-9]{0,2}")) {
      return new Denied(Denial.INVALID_SELECTOR);
    }
    int ordinal;
    try {
      ordinal = Integer.parseInt(selector);
    } catch (NumberFormatException malformed) {
      return new Denied(Denial.INVALID_SELECTOR);
    }
    if (ordinal > actors.size()) {
      return new Denied(Denial.SELECTOR_OUT_OF_RANGE);
    }
    return selected(roster, ordinal, actors.get(ordinal - 1));
  }

  private static Selected selected(
      ValidatedRoster roster, int ordinal, CanonicalGameplayRosterClient.RosterActor actor) {
    CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot = roster.snapshot();
    return new Selected(
        roster.canonicalAccountUuid(),
        snapshot.snapshotUuid(),
        snapshot.snapshotDigest(),
        snapshot.target(),
        actor.characterUuid(),
        actor.displayName(),
        ordinal);
  }

  private static boolean matchesExactPublishedTarget(
      CanonicalGameplayRosterTarget target, CanonicalPublishedPlayerRoute publishedRoute) {
    if (target == null || !target.getUnknownFields().asMap().isEmpty()) {
      return false;
    }
    var route = publishedRoute.route();
    RealmEntryPolicy policy = publishedRoute.entryPolicy();
    if (policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || !"SHARED".equals(route.playableStateScope())) {
      return false;
    }
    publishedRoute.policySetEvidence().requireValidDigest();
    publishedRoute.selectedPolicyEvidence().requireValidDigest(publishedRoute.policySetEvidence());
    return target.getTenantUuid().equals(route.canonicalTenantId().toString())
        && target.getRealmUuid().equals(route.realmId().toString())
        && target.getWorldSlug().equals(route.worldSlug())
        && target.getRealmSlug().equals(route.realmSlug())
        && target.getGameInstanceUuid().equals(route.canonicalGameInstanceId().toString())
        && target.getCatalogRevision() == route.catalogRevision()
        && target
            .getPublishedPolicyDigest()
            .equals(rawDigest(publishedRoute.selectedPolicyEvidence().policyDigest()))
        && target
            .getPublishedReleaseBundleRef()
            .equals(publishedRoute.policySetEvidence().publishedReleaseBundleRef())
        && target.getAdmissionPointerSnapshotDigest().equals(route.admissionPointerSnapshotDigest())
        && target.getPublishedOwnerProofDigest().equals(rawDigest(route.ownerProofDigest()))
        && target
            .getPlayableStateNamespaceUuid()
            .equals(route.playableStateNamespaceId().toString())
        && target.getPlayableStateScope() == PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED
        && target.getEntryPolicy()
            == CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY
        && target.getCanonicalVersionUuid().equals(route.canonicalVersionId().toString())
        && target.getPointerVersion() == route.pointerVersion()
        && target.getActiveWorldEpoch() == route.activeWorldEpoch();
  }

  private static String rawDigest(String prefixedDigest) {
    if (prefixedDigest == null || !prefixedDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Published route digest is malformed");
    }
    return prefixedDigest.substring("sha256:".length());
  }

  private static boolean isCanonicalDisplayName(String value) {
    return value != null
        && !value.isBlank()
        && value.equals(value.trim())
        && value.length() <= 100
        && value.codePoints().noneMatch(Character::isISOControl);
  }

  private static boolean isCanonicalDigest(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static boolean isNonNil(UUID value) {
    return value != null && !NIL_UUID.equals(value);
  }

  /** Snapshot-bound selection data; instances can only be created by {@link #validate}. */
  public static final class ValidatedRoster {
    private final CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot;
    private final UUID canonicalAccountUuid;
    private final CanonicalPublishedPlayerRoute route;

    private ValidatedRoster(
        CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot,
        UUID canonicalAccountUuid,
        CanonicalPublishedPlayerRoute route) {
      this.snapshot = snapshot;
      this.canonicalAccountUuid = canonicalAccountUuid;
      this.route = route;
    }

    public CanonicalGameplayRosterClient.PreseededRosterSnapshot snapshot() {
      return snapshot;
    }

    public UUID canonicalAccountUuid() {
      return canonicalAccountUuid;
    }

    public CanonicalPublishedPlayerRoute route() {
      return route;
    }

    public List<CanonicalGameplayRosterClient.RosterActor> actors() {
      return snapshot.actors();
    }
  }

  public sealed interface SelectionResult permits Selected, SelectionRequired, Denied {}

  /** The UUID is usable only as internal input to downstream exact target/account validation. */
  public static final class Selected implements SelectionResult {
    private final UUID canonicalAccountUuid;
    private final UUID rosterSnapshotUuid;
    private final String rosterSnapshotDigest;
    private final CanonicalGameplayRosterTarget target;
    private final UUID characterUuid;
    private final String displayName;
    private final int ordinal;

    private Selected(
        UUID canonicalAccountUuid,
        UUID rosterSnapshotUuid,
        String rosterSnapshotDigest,
        CanonicalGameplayRosterTarget target,
        UUID characterUuid,
        String displayName,
        int ordinal) {
      this.canonicalAccountUuid = canonicalAccountUuid;
      this.rosterSnapshotUuid = rosterSnapshotUuid;
      this.rosterSnapshotDigest = rosterSnapshotDigest;
      this.target = target;
      this.characterUuid = characterUuid;
      this.displayName = displayName;
      this.ordinal = ordinal;
    }

    public UUID canonicalAccountUuid() {
      return canonicalAccountUuid;
    }

    public UUID rosterSnapshotUuid() {
      return rosterSnapshotUuid;
    }

    public String rosterSnapshotDigest() {
      return rosterSnapshotDigest;
    }

    @SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "CanonicalGameplayRosterTarget is an immutable generated protobuf message.")
    public CanonicalGameplayRosterTarget target() {
      return target;
    }

    public UUID characterUuid() {
      return characterUuid;
    }

    public String displayName() {
      return displayName;
    }

    public int ordinal() {
      return ordinal;
    }
  }

  public record Choice(int ordinal, String displayName) {}

  public record SelectionRequired(List<Choice> choices) implements SelectionResult {
    public SelectionRequired {
      choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
    }
  }

  public record Denied(Denial reason) implements SelectionResult {
    public Denied {
      Objects.requireNonNull(reason, "reason");
    }
  }

  public enum Denial {
    INVALID_SNAPSHOT,
    NO_PRESEEDED_ACTOR,
    INVALID_SELECTOR,
    SELECTOR_OUT_OF_RANGE
  }
}
