package net.firedevops.firemud.common.gamesession;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/** Closed, bounded as-of facts from Game Session's current private-playtest source read. */
public final class CurrentPrivatePlaytestLifecycleReadEvidence {
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private CurrentPrivatePlaytestLifecycleReadEvidence() {}

  /** Exact canonical selector shared by the read request and returned source snapshot. */
  public record Scope(String targetNamespace, UUID tenantId, String worldSlug, String realmSlug) {
    public Scope {
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("Canonical target namespace required");
      }
      requireUuid(tenantId, "tenantId");
      requireSlug(worldSlug, "worldSlug");
      requireSlug(realmSlug, "realmSlug");
    }
  }

  /** Current selector result as observed at one Game Session database read boundary. */
  public record Snapshot(
      Scope scope,
      UUID selectionRequestId,
      long selectionHeadRevision,
      String selectionRequestDigest,
      Instant selectedAt,
      UUID preparationRequestId,
      String preparationRequestDigest,
      UUID playtestLifecycleId,
      UUID playableStateNamespaceId,
      long playtestStateGeneration,
      Instant forkExpiresAt,
      String preparationState,
      ReadState readState,
      Instant observedAt,
      UUID canonicalVersionId,
      UUID sourceCatalogRealmId,
      UUID sourceCatalogRequestId,
      long sourceCatalogRevision,
      String publishedReleaseBundleRef,
      String publishedReleaseBundleDigest,
      UUID privatePolicyId,
      String privatePolicyDigest,
      UUID policySourceCommitId,
      UUID policySourceRevisionId,
      String policyLogicalRevisionId,
      String policySetDigest) {
    public Snapshot {
      Objects.requireNonNull(scope, "scope");
      requireUuid(selectionRequestId, "selectionRequestId");
      requirePositive(selectionHeadRevision, "selectionHeadRevision");
      requireDigest(selectionRequestDigest, "selectionRequestDigest");
      requireInstant(selectedAt, "selectedAt");
      requireUuid(preparationRequestId, "preparationRequestId");
      requireDigest(preparationRequestDigest, "preparationRequestDigest");
      requireUuid(playtestLifecycleId, "playtestLifecycleId");
      requireUuid(playableStateNamespaceId, "playableStateNamespaceId");
      if (playtestStateGeneration != 1L) {
        throw new IllegalArgumentException("Initial private playtest generation must be one");
      }
      requireInstant(forkExpiresAt, "forkExpiresAt");
      if (!"PREPARING".equals(preparationState)) {
        throw new IllegalArgumentException("Only non-admissible PREPARING snapshots are supported");
      }
      Objects.requireNonNull(readState, "readState");
      requireInstant(observedAt, "observedAt");
      if (selectedAt.isAfter(observedAt)) {
        throw new IllegalArgumentException("Selection time must not follow the source observation");
      }
      ReadState expectedState =
          observedAt.isBefore(forkExpiresAt) ? ReadState.INCOMPLETE : ReadState.EXPIRED;
      if (readState != expectedState) {
        throw new IllegalArgumentException("Read state differs from source expiry and observation");
      }
      requireUuid(canonicalVersionId, "canonicalVersionId");
      requireUuid(sourceCatalogRealmId, "sourceCatalogRealmId");
      requireUuid(sourceCatalogRequestId, "sourceCatalogRequestId");
      if (sourceCatalogRevision != 1L) {
        throw new IllegalArgumentException("Initial source catalog revision must be one");
      }
      requireBoundText(publishedReleaseBundleRef, 1024, "publishedReleaseBundleRef");
      requireDigest(publishedReleaseBundleDigest, "publishedReleaseBundleDigest");
      requireUuid(privatePolicyId, "privatePolicyId");
      requireDigest(privatePolicyDigest, "privatePolicyDigest");
      requireUuid(policySourceCommitId, "policySourceCommitId");
      requireUuid(policySourceRevisionId, "policySourceRevisionId");
      requireBoundText(policyLogicalRevisionId, 128, "policyLogicalRevisionId");
      requireDigest(policySetDigest, "policySetDigest");
    }
  }

  /** The preparation's source-clock classification; neither value grants admission. */
  public enum ReadState {
    INCOMPLETE,
    EXPIRED
  }

  /**
   * Codec-qualified point-in-time response. The response is source evidence only, not a grant,
   * current caller authorization, continuing protection, or gameplay admission decision.
   */
  public record Evidence(
      CurrentPrivatePlaytestLifecycleReadGrpcCodec.Request request, Optional<Snapshot> current) {
    public Evidence {
      Objects.requireNonNull(request, "request");
      current = Objects.requireNonNull(current, "current");
      current.ifPresent(
          snapshot -> {
            if (!request.scope().equals(snapshot.scope())) {
              throw new IllegalArgumentException("Current snapshot target differs from request");
            }
          });
    }
  }

  private static void requireUuid(UUID value, String name) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(name + " must be a non-nil UUID");
    }
  }

  private static void requireSlug(String value, String name) {
    if (value == null || value.length() > 120 || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new IllegalArgumentException(name + " must be a canonical slug");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireInstant(Instant value, String name) {
    Objects.requireNonNull(value, name);
    long seconds = value.getEpochSecond();
    if (seconds < -62_135_596_800L || seconds > 253_402_300_799L) {
      throw new IllegalArgumentException(name + " must be between UTC years 0001 and 9999");
    }
  }

  private static void requireDigest(String value, String name) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
    }
  }

  private static void requireBoundText(String value, int maxCharacters, String name) {
    if (value == null
        || value.isBlank()
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.length() > maxCharacters) {
      throw new IllegalArgumentException(name + " is missing or exceeds its source character bound");
    }
  }
}
