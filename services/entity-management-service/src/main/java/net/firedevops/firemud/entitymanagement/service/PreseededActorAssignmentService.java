package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import net.firedevops.firemud.common.account.AccountActorStagingEligibilityEvidence;
import net.firedevops.firemud.common.account.RuntimeAccountIdentityEvidence;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;

/**
 * Entity-owned persistence boundary behind a dedicated run-owned bootstrap action. Successful
 * assignment persists a non-admitting PRESEEDED actor and receipt; it does not grant PLAY or
 * authorize runtime activation.
 */
public final class PreseededActorAssignmentService {
  private final RunOwnedPreseededAssignmentAuthority runAuthority;
  private final PreseededActorAccountIdentityPort accountIdentityPort;
  private final PreseededActorStagingEligibilityPort stagingEligibilityPort;
  private final PreseededActorAssignmentOwnerEvidencePort ownerEvidencePort;
  private final CharacterRepository characterRepository;

  public PreseededActorAssignmentService(
      RunOwnedPreseededAssignmentAuthority runAuthority,
      PreseededActorAccountIdentityPort accountIdentityPort,
      PreseededActorStagingEligibilityPort stagingEligibilityPort,
      PreseededActorAssignmentOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    this.runAuthority = Objects.requireNonNull(runAuthority, "runAuthority");
    this.accountIdentityPort = Objects.requireNonNull(accountIdentityPort, "accountIdentityPort");
    this.stagingEligibilityPort =
        Objects.requireNonNull(stagingEligibilityPort, "stagingEligibilityPort");
    this.ownerEvidencePort = Objects.requireNonNull(ownerEvidencePort, "ownerEvidencePort");
    this.characterRepository = Objects.requireNonNull(characterRepository, "characterRepository");
  }

  public PreseededActorAssignmentResult assign(PreseededActorAssignmentRequest request) {
    Objects.requireNonNull(request, "request");
    runAuthority.requireAuthorized(
        request, RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);

    RuntimeAccountIdentityEvidence accountIdentity =
        accountIdentityPort.resolveRuntimeAccountIdentity(
            request.canonicalAccountUuid().toString(), request.assignmentUuid().toString());
    requireExactAccountProof(request, accountIdentity);

    // One current Account staging snapshot is a non-admitting predicate, not held JOIN authority.
    AccountActorStagingEligibilityEvidence stagingEligibility =
        stagingEligibilityPort.resolvePreseededActorStagingEligibility(
            request.canonicalAccountUuid().toString(),
            request.expectedTarget().canonicalTenantUuid().toString(),
            request.assignmentUuid().toString());
    requireExactStagingProof(request, accountIdentity, stagingEligibility);

    PreseededActorAssignmentOwnerEvidence target =
        ownerEvidencePort.resolveCurrentEligibleTarget(
            request, accountIdentity, stagingEligibility);
    requireExactEligibleTarget(request, target, stagingEligibility);

    String intentDigest = PreseededActorAssignmentIntentDigest.compute(request, target);
    if (!request.mutationIntentDigest().equals(intentDigest)) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_MUTATION_INTENT_DIGEST_MISMATCH");
    }
    // Re-read the run-owned capability against the independently resolved target immediately
    // before entering the atomic owner repository operation.
    runAuthority.requireTargetBoundAuthorized(
        request, target, RunOwnedPreseededAssignmentAuthority.Action.PRESEEDED_ACTOR_ASSIGNMENT);
    return characterRepository.assignPreseededActor(request, target, accountIdentity, intentDigest);
  }

  private static void requireExactAccountProof(
      PreseededActorAssignmentRequest request, RuntimeAccountIdentityEvidence proof) {
    if (proof == null
        || !request.assignmentUuid().equals(proof.requestId())
        || !request.canonicalAccountUuid().equals(proof.canonicalAccountId())) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_IDENTITY_PROOF_MISMATCH");
    }
  }

  private static void requireExactStagingProof(
      PreseededActorAssignmentRequest request,
      RuntimeAccountIdentityEvidence identity,
      AccountActorStagingEligibilityEvidence staging) {
    if (staging == null
        || !request.assignmentUuid().equals(staging.requestId())
        || !request.canonicalAccountUuid().equals(staging.canonicalAccountId())
        || !request.expectedTarget().canonicalTenantUuid().equals(staging.canonicalTenantId())
        || !identity.targetNamespace().equals(staging.targetNamespace())
        || !identity.accountUuidProvenance().equals(staging.accountUuidProvenance())) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_STAGING_EVIDENCE_MISMATCH");
    }
    if (staging.purpose()
            != AccountActorStagingEligibilityEvidence.Purpose.PUBLIC_PRODUCTION_STAGING_ONLY
        || staging.currentness()
            != AccountActorStagingEligibilityEvidence.Currentness.CURRENT_AT_REVALIDATION) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_REVALIDATION_REQUIRED");
    }
    if (staging.decision() != AccountActorStagingEligibilityEvidence.Decision.STAGING_ELIGIBLE) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_NOT_CURRENTLY_ELIGIBLE");
    }
  }

  private void requireExactEligibleTarget(
      PreseededActorAssignmentRequest request,
      PreseededActorAssignmentOwnerEvidence target,
      AccountActorStagingEligibilityEvidence staging) {
    if (target == null
        || !request.assignmentUuid().equals(target.assignmentUuid())
        || !request.canonicalAccountUuid().equals(target.canonicalAccountUuid())) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_OWNER_PROOF_MISMATCH");
    }
    if (target.eligibility() != PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_NOT_CURRENTLY_ELIGIBLE");
    }
    if (target.accountPurpose()
            != PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING
        || target.accountCurrentness()
            != PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_REVALIDATION_REQUIRED");
    }
    if (target.entryPolicy()
        != PreseededActorAssignmentOwnerEvidence.PublishedEntryPolicy.PRESEEDED_ONLY) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_POLICY_MISMATCH");
    }
    if (!request.expectedTarget().exactlyMatches(target)) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    }
    if (!target.canonicalTenantUuid().equals(staging.canonicalTenantId())
        || target.eligibilityEvaluatedAt() == null
        || !target.eligibilityEvaluatedAt().equals(staging.observedAt())
        || target.membershipAuthorityGeneration() != staging.membershipAuthorityGeneration()
        || !target.eligibilityEvidenceDigest().equals(staging.eligibilityDecisionDigest())
        || !target.accountAuthoritySnapshotDigest().equals(staging.authoritySnapshotDigest())) {
      throw new IllegalStateException("PRESEEDED_ASSIGNMENT_ACCOUNT_STAGING_EVIDENCE_MISMATCH");
    }
  }
}
