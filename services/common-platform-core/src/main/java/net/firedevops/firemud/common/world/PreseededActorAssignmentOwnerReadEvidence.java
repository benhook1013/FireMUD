package net.firedevops.firemud.common.world;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublishedRealmEntryPolicyEvidence;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;

/**
 * Request-bound Game Session and Game Design source evidence for non-admitting actor assignment.
 * Account and assignment UUIDs are correlation selectors only; they do not establish membership,
 * actor ownership, JOIN, PLAY, or gameplay admission.
 */
public record PreseededActorAssignmentOwnerReadEvidence(
    Request request, CanonicalGameplayRosterOwnerReadEvidence sourceEvidence) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern RAW_SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final int MAX_RELEASE_REF_LENGTH = 1024;

  public PreseededActorAssignmentOwnerReadEvidence {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(sourceEvidence, "sourceEvidence");
    CanonicalGameplayRosterOwnerReadEvidence.Request exactSourceRequest =
        sourceRequest(request, sourceEvidence.gameSessionOwnerProof());
    if (!exactSourceRequest.equals(sourceEvidence.request())) {
      throw new IllegalArgumentException(
          "Game Session assignment source changed an exact request selector or owner counter");
    }
    PublishedRealmEntryPolicyEvidence selected = sourceEvidence.selectedPolicyEvidence();
    String selectedDigest = rawDigest(selected.policyDigest());
    if (!request.expectedPolicyDigest().equals(selectedDigest)
        || !request
            .expectedPublishedReleaseBundleRef()
            .equals(sourceEvidence.publishedPolicySetEvidence().publishedReleaseBundleRef())) {
      throw new IllegalArgumentException(
          "Game Design publication changed the exact policy or release selectors");
    }
  }

  /**
   * Builds the legacy exact-counter source request only from the returned owner proof. The
   * assignment RPC itself never accepts pointer-version or active-epoch guesses from Entity.
   */
  public static CanonicalGameplayRosterOwnerReadEvidence.Request sourceRequest(
      Request request, GameSessionCanonicalInitialAdmissionOwnerProof proof) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(proof, "proof");
    var hold = proof.holdIdentity().request();
    return new CanonicalGameplayRosterOwnerReadEvidence.Request(
        request.assignmentUuid(),
        request.canonicalAccountUuid(),
        request.targetNamespace(),
        request.canonicalTenantUuid(),
        request.worldSlug(),
        request.realmUuid(),
        request.realmSlug(),
        request.playableStateNamespaceUuid(),
        request.playableStateScope(),
        request.canonicalGameInstanceUuid(),
        request.canonicalVersionUuid(),
        request.expectedCatalogRevision(),
        Objects.requireNonNull(proof.committedPointerVersion(), "committedPointerVersion"),
        hold.activeLifecycleEpoch());
  }

  private static String rawDigest(String value) {
    if (value != null && value.matches("sha256:[0-9a-f]{64}")) {
      return value.substring("sha256:".length());
    }
    if (value != null && RAW_SHA256.matcher(value).matches()) {
      return value;
    }
    throw new IllegalArgumentException("Published policy digest must be canonical SHA-256");
  }

  /** Selector-only assignment request; current pointer and World epoch counters are absent. */
  public record Request(
      UUID assignmentUuid,
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
      String expectedPolicyDigest,
      String expectedPublishedReleaseBundleRef) {
    public Request {
      requireUuid(assignmentUuid, "assignmentUuid");
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
        throw new IllegalArgumentException("Only exact SHARED assignment targets are supported");
      }
      requireUuid(canonicalGameInstanceUuid, "canonicalGameInstanceUuid");
      requireUuid(canonicalVersionUuid, "canonicalVersionUuid");
      if (expectedCatalogRevision <= 0L) {
        throw new IllegalArgumentException("expectedCatalogRevision must be positive");
      }
      if (expectedPolicyDigest == null || !RAW_SHA256.matcher(expectedPolicyDigest).matches()) {
        throw new IllegalArgumentException("expectedPolicyDigest must be raw lowercase SHA-256");
      }
      if (expectedPublishedReleaseBundleRef == null
          || expectedPublishedReleaseBundleRef.isBlank()
          || !expectedPublishedReleaseBundleRef.equals(expectedPublishedReleaseBundleRef.trim())
          || expectedPublishedReleaseBundleRef.length() > MAX_RELEASE_REF_LENGTH
          || expectedPublishedReleaseBundleRef.codePoints().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("expectedPublishedReleaseBundleRef is incomplete");
      }
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
  }
}
