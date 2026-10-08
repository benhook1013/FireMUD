package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayRosterSelectedAssignmentServiceTest {
  @Test
  void resolvesCurrentTargetThenReturnsOnlyTheExactHistoricalAssignmentReference() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(), request.canonicalAccountUuid(), request.expectedTarget());
    CanonicalGameplayRosterOwnerEvidence evidence =
        evidence(ownerRequest, request.expectedTarget());
    PreseededActorAssignmentExpectedTarget assignmentTarget =
        assignmentTarget(request.expectedTarget());
    PreseededActorAssignmentReceipt receipt = receipt(request, assignmentTarget);
    when(ownerPort.resolveCurrentTarget(ownerRequest)).thenReturn(evidence);
    when(repository.readSelectedPreseededActorAssignmentReceipt(
            request.canonicalAccountUuid(),
            request.selectedCharacterUuid(),
            assignmentTarget,
            request.expectedTarget().publishedOwnerProofDigest()))
        .thenReturn(Optional.of(receipt));

    CanonicalGameplayRosterSelectedAssignmentReference result = service.read(request);

    assertThat(result.requestUuid()).isEqualTo(request.requestUuid());
    assertThat(result.canonicalAccountUuid()).isEqualTo(request.canonicalAccountUuid());
    assertThat(result.selectedCharacterUuid()).isEqualTo(request.selectedCharacterUuid());
    assertThat(result.target()).isEqualTo(request.expectedTarget());
    assertThat(result.assignmentUuid()).isEqualTo(receipt.assignmentUuid());
    assertThat(result.intentDigest()).isEqualTo(receipt.intentDigest());
    verify(ownerPort).resolveCurrentTarget(ownerRequest);
    verify(repository)
        .readSelectedPreseededActorAssignmentReceipt(
            request.canonicalAccountUuid(),
            request.selectedCharacterUuid(),
            assignmentTarget,
            request.expectedTarget().publishedOwnerProofDigest());
  }

  @Test
  void ownerFailureAndRequestAccountOrTargetMismatchFailBeforeRepositoryRead() {
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(), request.canonicalAccountUuid(), request.expectedTarget());
    CanonicalGameplayRosterOwnerEvidencePort unavailableOwnerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository unavailableRepository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService unavailableService =
        new CanonicalGameplayRosterSelectedAssignmentService(
            unavailableOwnerPort, unavailableRepository);

    when(unavailableOwnerPort.resolveCurrentTarget(ownerRequest))
        .thenThrow(
            new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException());
    assertThatThrownBy(() -> unavailableService.read(request))
        .isInstanceOf(
            CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException.class);
    verifyNoInteractions(unavailableRepository);

    assertOwnerMismatchBeforeRepository(
        request,
        new CanonicalGameplayRosterOwnerEvidence(
            request.requestUuid(),
            UUID.randomUUID(),
            request.expectedTarget(),
            Instant.parse("2026-10-01T00:00:00Z")),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    assertOwnerMismatchBeforeRepository(
        request,
        new CanonicalGameplayRosterOwnerEvidence(
            UUID.randomUUID(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            Instant.parse("2026-10-01T00:00:00Z")),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    assertOwnerMismatchBeforeRepository(
        request,
        evidence(ownerRequest, withNamespace(request.expectedTarget(), UUID.randomUUID())),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    assertOwnerMismatchBeforeRepository(
        request,
        evidence(
            ownerRequest,
            withScope(request.expectedTarget(), PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED)),
        UnsupportedOperationException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_TARGET_UNSUPPORTED");
    assertOwnerMismatchBeforeRepository(
        request,
        evidence(ownerRequest, withCatalogRevision(request.expectedTarget(), 4L)),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
  }

  @Test
  void unsupportedCallerPolicyAndScopeFailBeforeOwnerResolution() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);

    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        withPolicy(target(), CanonicalGameplayRosterEntryPolicy.AUTO_PROVISIONED))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                service.read(
                    request(withScope(target(), PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED))))
        .isInstanceOf(UnsupportedOperationException.class);

    verifyNoInteractions(ownerPort, repository);
  }

  @Test
  void ambientTransactionOrSynchronizationFailsBeforeOwnerAndRepositoryAccess() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("SELECTED_PRESEEDED_ASSIGNMENT_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(ownerPort, repository);

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("SELECTED_PRESEEDED_ASSIGNMENT_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(ownerPort, repository);
  }

  @Test
  void missingOrInvalidStoredAssignmentFailsClosed() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(), request.canonicalAccountUuid(), request.expectedTarget());
    when(ownerPort.resolveCurrentTarget(ownerRequest))
        .thenReturn(evidence(ownerRequest, request.expectedTarget()));
    when(repository.readSelectedPreseededActorAssignmentReceipt(
            request.canonicalAccountUuid(),
            request.selectedCharacterUuid(),
            assignmentTarget(request.expectedTarget()),
            request.expectedTarget().publishedOwnerProofDigest()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SELECTED_PRESEEDED_ASSIGNMENT_UNAVAILABLE");
  }

  private static CanonicalGameplayRosterSelectedAssignmentReadRequest request(
      CanonicalGameplayRosterTarget target) {
    return new CanonicalGameplayRosterSelectedAssignmentReadRequest(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), target);
  }

  private static void assertOwnerMismatchBeforeRepository(
      CanonicalGameplayRosterSelectedAssignmentReadRequest request,
      CanonicalGameplayRosterOwnerEvidence evidence,
      Class<? extends Throwable> expectedType,
      String expectedMessage) {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(), request.canonicalAccountUuid(), request.expectedTarget());
    when(ownerPort.resolveCurrentTarget(ownerRequest)).thenReturn(evidence);

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(expectedType)
        .hasMessage(expectedMessage);
    verifyNoInteractions(repository);
  }

  private static CanonicalGameplayRosterOwnerEvidence evidence(
      CanonicalGameplayRosterReadRequest request, CanonicalGameplayRosterTarget target) {
    return new CanonicalGameplayRosterOwnerEvidence(
        request.requestUuid(),
        request.canonicalAccountUuid(),
        target,
        Instant.parse("2026-10-01T00:00:00Z"));
  }

  private static CanonicalGameplayRosterTarget target() {
    return new CanonicalGameplayRosterTarget(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "world",
        "realm",
        UUID.randomUUID(),
        3L,
        17L,
        29L,
        UUID.fromString("17000000-0000-4000-8000-000000000017"),
        "1".repeat(64),
        "release/test",
        "2".repeat(64),
        "4".repeat(64),
        UUID.randomUUID(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
  }

  private static PreseededActorAssignmentExpectedTarget assignmentTarget(
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

  private static PreseededActorAssignmentReceipt receipt(
      CanonicalGameplayRosterSelectedAssignmentReadRequest request,
      PreseededActorAssignmentExpectedTarget target) {
    UUID assignmentUuid = UUID.randomUUID();
    PreseededActorCorePayload corePayload =
        new PreseededActorCorePayload(PreseededActorCorePayload.ActorKind.PLAYER, "Selected Actor");
    String intentDigest =
        new PreseededActorAssignmentRequest(
                assignmentUuid, request.canonicalAccountUuid(), corePayload, target)
            .mutationIntentDigest();
    return new PreseededActorAssignmentReceipt(
        assignmentUuid,
        request.canonicalAccountUuid(),
        request.selectedCharacterUuid(),
        intentDigest,
        target,
        corePayload,
        1,
        "dev",
        23L,
        "ACCOUNT_DATABASE_INSERT",
        PreseededActorAssignmentOwnerEvidence.Eligibility.ELIGIBLE,
        Instant.parse("2026-10-01T00:00:00Z"),
        4L,
        "5".repeat(64),
        "6".repeat(64),
        PreseededActorAssignmentOwnerEvidence.AccountPurpose.PRESEEDED_ACTOR_STAGING,
        PreseededActorAssignmentOwnerEvidence.AccountCurrentness.CURRENT_AT_REVALIDATION,
        request.expectedTarget().publishedOwnerProofDigest(),
        target.publishedReleaseBundleRef(),
        "PRESEEDED_ONLY",
        "ASSIGNED");
  }

  private static CanonicalGameplayRosterTarget withNamespace(
      CanonicalGameplayRosterTarget target, UUID namespaceUuid) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        namespaceUuid,
        target.playableStateScope(),
        target.entryPolicy());
  }

  private static CanonicalGameplayRosterTarget withScope(
      CanonicalGameplayRosterTarget target, PlayableStateScope scope) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        scope,
        target.entryPolicy());
  }

  private static CanonicalGameplayRosterTarget withCatalogRevision(
      CanonicalGameplayRosterTarget target, long catalogRevision) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        catalogRevision,
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.entryPolicy());
  }

  private static CanonicalGameplayRosterTarget withPolicy(
      CanonicalGameplayRosterTarget target, CanonicalGameplayRosterEntryPolicy policy) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        target.pointerVersion(),
        target.activeWorldEpoch(),
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        policy);
  }
}
