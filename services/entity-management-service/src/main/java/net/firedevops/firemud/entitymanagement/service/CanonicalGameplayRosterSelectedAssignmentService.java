package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Non-admitting read of the exact committed assignment for a selected PRESEEDED actor. */
public final class CanonicalGameplayRosterSelectedAssignmentService {
  private final CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort;
  private final CharacterRepository characterRepository;

  public CanonicalGameplayRosterSelectedAssignmentService(
      CanonicalGameplayRosterOwnerEvidencePort ownerEvidencePort,
      CharacterRepository characterRepository) {
    this.ownerEvidencePort = Objects.requireNonNull(ownerEvidencePort, "ownerEvidencePort");
    this.characterRepository = Objects.requireNonNull(characterRepository, "characterRepository");
  }

  public CanonicalGameplayRosterSelectedAssignmentReference read(
      CanonicalGameplayRosterSelectedAssignmentReadRequest request) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(request, "request");
    requireSupportedTarget(request.expectedTarget());

    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(), request.canonicalAccountUuid(), request.expectedTarget());
    CanonicalGameplayRosterOwnerEvidence evidence =
        ownerEvidencePort.resolveCurrentTarget(ownerRequest);
    if (evidence == null) {
      throw new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException();
    }
    requireSupportedTarget(evidence.target());
    if (!request.requestUuid().equals(evidence.requestUuid())
        || !request.canonicalAccountUuid().equals(evidence.canonicalAccountUuid())
        || !request.expectedTarget().equals(evidence.target())) {
      throw new IllegalStateException("SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    }

    PreseededActorAssignmentExpectedTarget expectedAssignmentTarget =
        expectedAssignmentTarget(evidence.target());
    PreseededActorAssignmentReceipt receipt =
        characterRepository
            .readSelectedPreseededActorAssignmentReceipt(
                request.canonicalAccountUuid(),
                request.selectedCharacterUuid(),
                expectedAssignmentTarget,
                evidence.target().publishedOwnerProofDigest())
            .orElseThrow(
                () -> new IllegalStateException("SELECTED_PRESEEDED_ASSIGNMENT_UNAVAILABLE"));

    if (!request.canonicalAccountUuid().equals(receipt.canonicalAccountUuid())
        || !request.selectedCharacterUuid().equals(receipt.characterUuid())
        || !request
            .expectedTarget()
            .publishedOwnerProofDigest()
            .equals(receipt.publishedOwnerProofDigest())
        || !expectedAssignmentTarget.equals(receipt.target())) {
      throw new IllegalStateException("SELECTED_PRESEEDED_ASSIGNMENT_RECEIPT_MISMATCH");
    }

    return new CanonicalGameplayRosterSelectedAssignmentReference(
        request.requestUuid(),
        request.canonicalAccountUuid(),
        request.selectedCharacterUuid(),
        request.expectedTarget(),
        receipt.assignmentUuid(),
        receipt.intentDigest());
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException(
          "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
  }

  private static void requireSupportedTarget(CanonicalGameplayRosterTarget target) {
    if (target.entryPolicy() != CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY
        || target.playableStateScope() != PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED) {
      throw new UnsupportedOperationException("SELECTED_PRESEEDED_ASSIGNMENT_TARGET_UNSUPPORTED");
    }
  }

  private static PreseededActorAssignmentExpectedTarget expectedAssignmentTarget(
      CanonicalGameplayRosterTarget target) {
    return new PreseededActorAssignmentExpectedTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid().toString(),
        target.catalogRevision(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.playableStateNamespaceId(),
        target.publishedReleaseBundleRef(),
        target.playableStateScope());
  }
}
