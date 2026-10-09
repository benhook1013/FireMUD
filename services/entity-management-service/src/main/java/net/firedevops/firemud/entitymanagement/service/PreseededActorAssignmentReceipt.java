package net.firedevops.firemud.entitymanagement.service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;

/**
 * Immutable readback of a committed, non-admitting PRESEEDED actor assignment and its owner
 * snapshots. {@code CURRENT_AT_REVALIDATION} records an Account observation made at that point in
 * time; it is neither a held Account authorization nor a PLAY grant, and this receipt does not
 * authorize runtime admission or activation.
 */
public record PreseededActorAssignmentReceipt(
    UUID assignmentUuid,
    UUID canonicalAccountUuid,
    UUID characterUuid,
    String intentDigest,
    PreseededActorAssignmentExpectedTarget target,
    PreseededActorCorePayload corePayload,
    int accountIdentitySchemaVersion,
    String accountIdentityTargetNamespace,
    long sourceAccountRowId,
    String accountUuidProvenance,
    PreseededActorAssignmentOwnerEvidence.Eligibility eligibility,
    Instant eligibilityEvaluatedAt,
    long membershipAuthorityGeneration,
    String eligibilityEvidenceDigest,
    String accountAuthoritySnapshotDigest,
    PreseededActorAssignmentOwnerEvidence.AccountPurpose accountPurpose,
    PreseededActorAssignmentOwnerEvidence.AccountCurrentness accountCurrentness,
    String publishedOwnerProofDigest,
    String publishedReleaseBundleRef,
    String entryPolicy,
    String status) {
  public PreseededActorAssignmentReceipt {
    Objects.requireNonNull(assignmentUuid, "assignmentUuid");
    Objects.requireNonNull(canonicalAccountUuid, "canonicalAccountUuid");
    Objects.requireNonNull(characterUuid, "characterUuid");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(corePayload, "corePayload");
    Objects.requireNonNull(eligibility, "eligibility");
    Objects.requireNonNull(eligibilityEvaluatedAt, "eligibilityEvaluatedAt");
    Objects.requireNonNull(accountPurpose, "accountPurpose");
    Objects.requireNonNull(accountCurrentness, "accountCurrentness");
    if (intentDigest == null
        || !intentDigest.matches("[0-9a-f]{64}")
        || accountIdentitySchemaVersion != 1
        || !GrpcPeerIdentity.isValidNamespace(accountIdentityTargetNamespace)
        || sourceAccountRowId <= 0L
        || !isRecognizedAccountUuidProvenance(accountUuidProvenance)
        || eligibility != PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE
        || membershipAuthorityGeneration <= 0
        || eligibilityEvidenceDigest == null
        || !eligibilityEvidenceDigest.matches("[0-9a-f]{64}")
        || accountAuthoritySnapshotDigest == null
        || !accountAuthoritySnapshotDigest.matches("[0-9a-f]{64}")
        || publishedOwnerProofDigest == null
        || !publishedOwnerProofDigest.matches("[0-9a-f]{64}")
        || publishedReleaseBundleRef == null
        || publishedReleaseBundleRef.isBlank()
        || publishedReleaseBundleRef.length() > 1024
        || accountPurpose
            != PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING
        || accountCurrentness
            != PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION
        || !"PRESEEDED_ONLY".equals(entryPolicy)
        || !target.publishedReleaseBundleRef().equals(publishedReleaseBundleRef)
        || !"ASSIGNED".equals(status)) {
      throw new IllegalArgumentException("Committed PRESEEDED assignment receipt is incomplete");
    }
    PreseededActorAssignmentRequest intent =
        new PreseededActorAssignmentRequest(
            assignmentUuid, canonicalAccountUuid, corePayload, target);
    if (!intentDigest.equals(intent.mutationIntentDigest())) {
      throw new IllegalArgumentException("Committed PRESEEDED assignment intent digest is invalid");
    }
  }

  /**
   * Returns the persisted point-in-time result; it does not assert eligibility is still current.
   */
  public boolean eligibleAtRevalidation() {
    return eligibility == PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE;
  }

  private static boolean isRecognizedAccountUuidProvenance(String provenance) {
    return "ACCOUNT_V29_MIGRATION".equals(provenance)
        || "ACCOUNT_REPOSITORY_INSERT".equals(provenance)
        || "ACCOUNT_DATABASE_INSERT".equals(provenance);
  }
}
