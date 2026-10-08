package net.firedevops.firemud.gamesession.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact Game Session owner readback for one canonical first-OPEN request. */
public record CanonicalInitialAdmissionOwnerProof(
    Outcome outcome,
    String initialAdmissionRequestId,
    String requestDigest,
    String targetNamespace,
    UUID canonicalTenantId,
    String worldSlug,
    UUID realmId,
    UUID playableStateNamespaceId,
    String playableStateScope,
    UUID canonicalGameInstanceId,
    UUID canonicalVersionId,
    long activeLifecycleEpoch,
    long expectedCatalogRevision,
    CanonicalInitialAdmissionRequest.OriginKind originKind,
    Long expectedPriorPointerVersion,
    UUID holdId,
    UUID holdFence,
    String holdBindingDigest,
    Long committedPointerVersion,
    Long auditEventId,
    String proofDigest,
    boolean positiveDurableAbort,
    Instant terminalAt) {
  public enum Outcome {
    PENDING,
    COMMITTED,
    ABORTED
  }

  private static final Pattern REQUEST_DIGEST = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern PROOF_DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

  public CanonicalInitialAdmissionOwnerProof {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(initialAdmissionRequestId, "initialAdmissionRequestId");
    Objects.requireNonNull(requestDigest, "requestDigest");
    Objects.requireNonNull(targetNamespace, "targetNamespace");
    Objects.requireNonNull(canonicalTenantId, "canonicalTenantId");
    Objects.requireNonNull(worldSlug, "worldSlug");
    Objects.requireNonNull(realmId, "realmId");
    Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
    Objects.requireNonNull(playableStateScope, "playableStateScope");
    Objects.requireNonNull(canonicalGameInstanceId, "canonicalGameInstanceId");
    Objects.requireNonNull(canonicalVersionId, "canonicalVersionId");
    Objects.requireNonNull(originKind, "originKind");
    Objects.requireNonNull(holdId, "holdId");
    Objects.requireNonNull(holdFence, "holdFence");
    Objects.requireNonNull(holdBindingDigest, "holdBindingDigest");
    if (!REQUEST_DIGEST.matcher(requestDigest).matches()
        || !PROOF_DIGEST.matcher(holdBindingDigest).matches()) {
      throw new IllegalArgumentException("Owner proof digest fields have invalid representations");
    }
    if (activeLifecycleEpoch <= 0L || expectedCatalogRevision <= 0L) {
      throw new IllegalArgumentException("Owner proof contains a non-positive canonical fence");
    }
    if (outcome == Outcome.COMMITTED) {
      if (committedPointerVersion == null
          || committedPointerVersion <= 0L
          || auditEventId == null
          || auditEventId <= 0L
          || proofDigest == null
          || !PROOF_DIGEST.matcher(proofDigest).matches()
          || terminalAt == null
          || positiveDurableAbort) {
        throw new IllegalArgumentException(
            "COMMITTED proof must carry its exact pointer and audit");
      }
    } else if (outcome == Outcome.ABORTED) {
      if (committedPointerVersion != null
          || auditEventId != null
          || proofDigest == null
          || !PROOF_DIGEST.matcher(proofDigest).matches()
          || terminalAt == null
          || !positiveDurableAbort) {
        throw new IllegalArgumentException(
            "ABORTED proof must carry positive durable fencing and no commit result");
      }
    } else if (committedPointerVersion != null
        || auditEventId != null
        || proofDigest != null
        || positiveDurableAbort
        || terminalAt != null) {
      throw new IllegalArgumentException("PENDING is not terminal owner proof");
    }
  }

  public boolean provesCommit() {
    return outcome == Outcome.COMMITTED;
  }

  public boolean provesAbort() {
    return outcome == Outcome.ABORTED && positiveDurableAbort;
  }
}
