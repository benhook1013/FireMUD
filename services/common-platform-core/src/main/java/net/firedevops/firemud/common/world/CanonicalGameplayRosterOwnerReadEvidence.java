package net.firedevops.firemud.common.world;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicySetEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/**
 * Read-only Entity-facing source evidence for one exact current public-production roster target.
 * The account UUID is request correlation only; this evidence does not establish Account authority,
 * actor ownership, or gameplay admission.
 */
public record CanonicalGameplayRosterOwnerReadEvidence(
    Request request,
    String admissionPointerSnapshotDigest,
    GameSessionCanonicalInitialAdmissionOwnerProof gameSessionOwnerProof,
    PublishedRealmEntryPolicySetEvidence publishedPolicySetEvidence) {
  public static final int MAX_OWNER_PROOF_BYTES = 16 * 1024;
  public static final int MAX_POLICY_SET_BYTES = 2 * 1024 * 1024;

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern POINTER_SNAPSHOT_DIGEST = Pattern.compile("[0-9a-f]{64}");

  public CanonicalGameplayRosterOwnerReadEvidence {
    Objects.requireNonNull(request, "request");
    if (admissionPointerSnapshotDigest == null
        || !POINTER_SNAPSHOT_DIGEST.matcher(admissionPointerSnapshotDigest).matches()) {
      throw new IllegalArgumentException(
          "Admission pointer snapshot digest must be raw lowercase 64-character SHA-256 hex");
    }
    Objects.requireNonNull(gameSessionOwnerProof, "gameSessionOwnerProof");
    Objects.requireNonNull(publishedPolicySetEvidence, "publishedPolicySetEvidence");

    byte[] proofBytes =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.canonicalBytes(gameSessionOwnerProof);
    byte[] policyBytes = publishedPolicySetEvidence.canonicalBytes();
    if (proofBytes.length > MAX_OWNER_PROOF_BYTES || policyBytes.length > MAX_POLICY_SET_BYTES) {
      throw new IllegalArgumentException("Canonical roster owner evidence exceeds its size bound");
    }
    gameSessionOwnerProof =
        GameSessionCanonicalInitialAdmissionOwnerProofCodec.fromStored(proofBytes);
    publishedPolicySetEvidence = PublishedRealmEntryPolicySetEvidence.fromStored(policyBytes);
    requireProofMatches(request, gameSessionOwnerProof);
    requirePolicyMatches(request, publishedPolicySetEvidence);
  }

  public PublishedRealmEntryPolicyEvidence selectedPolicyEvidence() {
    return publishedPolicySetEvidence.policies().stream()
        .filter(
            evidence ->
                evidence.policy().worldSlug().equals(request.worldSlug())
                    && evidence.policy().realmSlug().equals(request.realmSlug()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Exact published policy is unavailable"));
  }

  private static void requireProofMatches(
      Request request, GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    var hold = proof.holdIdentity().request();
    if (proof.outcome() != GameSessionCanonicalInitialAdmissionOwnerProof.Outcome.COMMITTED
        || !hold.targetNamespace().equals(request.targetNamespace())
        || !hold.canonicalTenantId().equals(request.canonicalTenantUuid())
        || !hold.worldSlug().equals(request.worldSlug())
        || !hold.realmId().equals(request.realmUuid())
        || !hold.playableStateNamespaceId().equals(request.playableStateNamespaceUuid())
        || !hold.playableStateScope().equals(request.playableStateScope())
        || !hold.canonicalGameInstanceId().equals(request.canonicalGameInstanceUuid())
        || !hold.canonicalVersionId().equals(request.canonicalVersionUuid())
        || hold.expectedCatalogRevision() != request.expectedCatalogRevision()
        || proof.committedPointerVersion() != request.expectedPointerVersion()
        || hold.activeLifecycleEpoch() != request.expectedActiveWorldEpoch()) {
      throw new IllegalArgumentException(
          "Game Session proof is not the exact current committed roster target");
    }
  }

  private static void requirePolicyMatches(
      Request request, PublishedRealmEntryPolicySetEvidence evidence) {
    if (!evidence.target().canonicalTenantId().equals(request.canonicalTenantUuid())
        || !evidence.target().canonicalVersionId().equals(request.canonicalVersionUuid())) {
      throw new IllegalArgumentException("Game Design policy set changed the exact tenant/version");
    }
    List<PublishedRealmEntryPolicyEvidence> matches =
        evidence.policies().stream()
            .filter(
                policy ->
                    policy.policy().worldSlug().equals(request.worldSlug())
                        && policy.policy().realmSlug().equals(request.realmSlug()))
            .toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Complete Game Design policy set lacks one exact selector");
    }
    RealmEntryPolicy policy = matches.getFirst().policy();
    if (!policy.visible()
        || !policy.publicProduction()
        || policy.stateScope() != RealmEntryPolicy.StateScope.SHARED
        || policy.entryPolicy() != RealmEntryPolicy.EntryPolicy.PRESEEDED_ONLY
        || !"SHARED".equals(request.playableStateScope())) {
      throw new IllegalArgumentException(
          "Exact route policy is not visible public-production SHARED PRESEEDED_ONLY");
    }
  }

  /** Closed selectors and exact current owner counters. Account identity is not an authority. */
  public record Request(
      UUID requestUuid,
      UUID canonicalAccountUuid,
      String targetNamespace,
      UUID canonicalTenantUuid,
      String worldSlug,
      UUID realmUuid,
      String realmSlug,
      UUID playableStateNamespaceUuid,
      String playableStateScope,
      UUID canonicalGameInstanceUuid,
      UUID canonicalVersionUuid,
      long expectedCatalogRevision,
      long expectedPointerVersion,
      long expectedActiveWorldEpoch) {
    public Request {
      requireUuid(requestUuid, "requestUuid");
      requireUuid(canonicalAccountUuid, "canonicalAccountUuid");
      if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
        throw new IllegalArgumentException("targetNamespace must be a valid workload namespace");
      }
      requireUuid(canonicalTenantUuid, "canonicalTenantUuid");
      requireSlug(worldSlug, "worldSlug");
      requireUuid(realmUuid, "realmUuid");
      requireSlug(realmSlug, "realmSlug");
      requireUuid(playableStateNamespaceUuid, "playableStateNamespaceUuid");
      if (!"SHARED".equals(playableStateScope)) {
        throw new IllegalArgumentException("Only exact SHARED roster targets are supported");
      }
      requireUuid(canonicalGameInstanceUuid, "canonicalGameInstanceUuid");
      requireUuid(canonicalVersionUuid, "canonicalVersionUuid");
      requirePositive(expectedCatalogRevision, "expectedCatalogRevision");
      requirePositive(expectedPointerVersion, "expectedPointerVersion");
      requirePositive(expectedActiveWorldEpoch, "expectedActiveWorldEpoch");
    }

    private static void requireSlug(String value, String name) {
      if (!RealmEntryPolicy.isCanonicalSlug(value)) {
        throw new IllegalArgumentException(name + " must be a canonical selector");
      }
    }

    private static void requireUuid(UUID value, String name) {
      Objects.requireNonNull(value, name);
      if (NIL_UUID.equals(value)) {
        throw new IllegalArgumentException(name + " must be a canonical non-nil UUID");
      }
    }

    private static void requirePositive(long value, String name) {
      if (value <= 0L) {
        throw new IllegalArgumentException(name + " must be positive");
      }
    }
  }
}
