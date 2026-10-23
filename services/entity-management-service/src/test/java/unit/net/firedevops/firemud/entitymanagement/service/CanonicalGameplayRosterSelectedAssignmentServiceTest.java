package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
  private static CanonicalGameplayRosterOwnerEvidencePort ownerPort() {
    return mock(
        CanonicalGameplayRosterOwnerEvidencePort.class,
        call -> {
          if (call.getMethod().getName().equals("readOriginalSelection")) {
            CanonicalGameplayRosterReadRequest request = call.getArgument(0);
            var binding =
                net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence
                    .request(
                        request.requestUuid(),
                        request.canonicalAccountUuid(),
                        request.playerExecutionContext().sessionUuid(),
                        "gameplay",
                        request.expectedTarget().toWire());
            // This unit source double uses the same exact snapshot fixture as the selected request.
            var selected = request(request.expectedTarget());
            var receipt =
                net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse
                    .newBuilder()
                    .setCanonicalAccountUuid(request.canonicalAccountUuid().toString())
                    .setTarget(request.expectedTarget().toWire())
                    .setSnapshotUuid(selected.expectedSnapshot().snapshotUuid().toString())
                    .setSnapshotDigest(selected.expectedSnapshot().snapshotDigest())
                    .addActors(
                        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterActor
                            .newBuilder()
                            .setCharacterUuid(
                                request.playerExecutionContext().characterUuid().toString())
                            .setDisplayName("Fixture Actor")
                            .setActorKind(
                                net.firedevops.firemud.entitymanagement.v1
                                    .CanonicalGameplayRosterActorKind
                                    .CANONICAL_ROSTER_ACTOR_KIND_PLAYER))
                    .build();
            return net
                .firedevops
                .firemud
                .common
                .world
                .CanonicalGameplayRosterReferenceEvidence
                .response(
                    binding,
                    1L,
                    java.util.UUID.fromString("12121212-1212-4212-8212-121212121212"),
                    receipt)
                .toBuilder()
                .setSelectionInput(
                    net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterSelectionInput
                        .newBuilder()
                        .setSchemaVersion(1))
                .setSelectedCharacterUuid(
                    request.playerExecutionContext().characterUuid().toString())
                .build();
          }
          return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
  }

  @Test
  void resolvesCurrentTargetThenReturnsOnlyTheExactHistoricalAssignmentReference() {
    assertResolvesCurrentTarget(target());
    assertResolvesCurrentTarget(
        withScope(target(), PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
  }

  private static void assertResolvesCurrentTarget(CanonicalGameplayRosterTarget target) {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target);
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
    CanonicalGameplayRosterOwnerEvidence evidence =
        evidence(ownerRequest, request.expectedTarget());
    PreseededActorAssignmentExpectedTarget assignmentTarget =
        assignmentTarget(request.expectedTarget());
    PreseededActorAssignmentReceipt receipt = receipt(request, assignmentTarget);
    when(ownerPort.resolveCurrentTarget(ownerRequest)).thenReturn(evidence);
    when(repository.readCurrentCanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(), request.expectedTarget(), request.expectedSnapshot()))
        .thenReturn(snapshot(request));
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
    assertThat(result.snapshot()).isEqualTo(request.expectedSnapshot());
    assertThat(result.assignmentUuid()).isEqualTo(receipt.assignmentUuid());
    assertThat(result.intentDigest()).isEqualTo(receipt.intentDigest());
    verify(ownerPort).resolveCurrentTarget(ownerRequest);
    verify(repository)
        .readCurrentCanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(), request.expectedTarget(), request.expectedSnapshot());
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
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
    CanonicalGameplayRosterOwnerEvidencePort unavailableOwnerPort = ownerPort();
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
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    CanonicalGameplayRosterSelectedAssignmentReadRequest isolatedRequest =
        request(withScope(target(), PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    CanonicalGameplayRosterReadRequest isolatedOwnerRequest =
        new CanonicalGameplayRosterReadRequest(
            isolatedRequest.requestUuid(),
            isolatedRequest.canonicalAccountUuid(),
            isolatedRequest.expectedTarget(),
            isolatedRequest.playerExecutionContext());
    assertOwnerMismatchBeforeRepository(
        isolatedRequest,
        evidence(
            isolatedOwnerRequest,
            withScope(
                isolatedRequest.expectedTarget(), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
    assertOwnerMismatchBeforeRepository(
        request,
        evidence(ownerRequest, withCatalogRevision(request.expectedTarget(), 4L)),
        IllegalStateException.class,
        "SELECTED_PRESEEDED_ASSIGNMENT_OWNER_TARGET_MISMATCH");
  }

  @Test
  void unsupportedCallerPolicyFailsBeforeOwnerResolution() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);

    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        withPolicy(target(), CanonicalGameplayRosterEntryPolicy.AUTO_PROVISIONED))))
        .isInstanceOf(UnsupportedOperationException.class);
    verifyNoInteractions(ownerPort, repository);
  }

  @Test
  void ambientTransactionOrSynchronizationFailsBeforeOwnerAndRepositoryAccess() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
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
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
    when(ownerPort.resolveCurrentTarget(ownerRequest))
        .thenReturn(evidence(ownerRequest, request.expectedTarget()));
    when(repository.readCurrentCanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(), request.expectedTarget(), request.expectedSnapshot()))
        .thenReturn(snapshot(request));
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

  @Test
  void isolatedSelectionRejectsSharedAssignmentReceipt() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request =
        request(withScope(target(), PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED));
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
    PreseededActorAssignmentExpectedTarget isolatedTarget =
        assignmentTarget(request.expectedTarget());
    PreseededActorAssignmentExpectedTarget sharedTarget =
        assignmentTarget(
            withScope(request.expectedTarget(), PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED));
    when(ownerPort.resolveCurrentTarget(ownerRequest))
        .thenReturn(evidence(ownerRequest, request.expectedTarget()));
    when(repository.readCurrentCanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(), request.expectedTarget(), request.expectedSnapshot()))
        .thenReturn(snapshot(request));
    when(repository.readSelectedPreseededActorAssignmentReceipt(
            request.canonicalAccountUuid(),
            request.selectedCharacterUuid(),
            isolatedTarget,
            request.expectedTarget().publishedOwnerProofDigest()))
        .thenReturn(Optional.of(receipt(request, sharedTarget)));

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SELECTED_PRESEEDED_ASSIGNMENT_RECEIPT_MISMATCH");
  }

  @Test
  void actorAbsentFromExpectedSnapshotFailsBeforeReceiptRead() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterSelectedAssignmentReadRequest request = request(target());
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
    when(ownerPort.resolveCurrentTarget(ownerRequest))
        .thenReturn(evidence(ownerRequest, request.expectedTarget()));
    when(repository.readCurrentCanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(), request.expectedTarget(), request.expectedSnapshot()))
        .thenReturn(
            new CanonicalGameplayRosterSnapshot(
                request.canonicalAccountUuid(),
                request.expectedSnapshot().snapshotUuid(),
                request.expectedSnapshot().snapshotDigest(),
                request.expectedTarget(),
                java.util.List.of(new CanonicalGameplayRosterActor(UUID.randomUUID(), "Other"))));

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("SELECTED_PRESEEDED_ASSIGNMENT_NOT_IN_EXPECTED_SNAPSHOT");
    verify(repository, never())
        .readSelectedPreseededActorAssignmentReceipt(
            request.canonicalAccountUuid(),
            request.selectedCharacterUuid(),
            assignmentTarget(request.expectedTarget()),
            request.expectedTarget().publishedOwnerProofDigest());
  }

  private static CanonicalGameplayRosterSelectedAssignmentReadRequest request(
      CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    UUID selectedUuid = UUID.randomUUID();
    return new CanonicalGameplayRosterSelectedAssignmentReadRequest(
        requestUuid,
        accountUuid,
        selectedUuid,
        target,
        new CanonicalGameplayRosterSnapshotReference(
            UUID.fromString("88888888-8888-4888-8888-888888888888"), "a".repeat(64)),
        executionContext(target, requestUuid, accountUuid, selectedUuid));
  }

  private static CanonicalGameplayRosterExecutionContext executionContext(
      CanonicalGameplayRosterTarget target,
      UUID requestUuid,
      UUID accountUuid,
      UUID characterUuid) {
    return new CanonicalGameplayRosterExecutionContext(
        accountUuid,
        target.tenantUuid(),
        target.playableStateNamespaceId(),
        target.gameInstanceUuid(),
        characterUuid,
        UUID.randomUUID(),
        target.realmUuid(),
        requestUuid,
        target.playableStateScope());
  }

  private static CanonicalGameplayRosterSnapshot snapshot(
      CanonicalGameplayRosterSelectedAssignmentReadRequest request) {
    return new CanonicalGameplayRosterSnapshot(
        request.canonicalAccountUuid(),
        request.expectedSnapshot().snapshotUuid(),
        request.expectedSnapshot().snapshotDigest(),
        request.expectedTarget(),
        java.util.List.of(
            new CanonicalGameplayRosterActor(request.selectedCharacterUuid(), "Selected Actor")));
  }

  private static void assertOwnerMismatchBeforeRepository(
      CanonicalGameplayRosterSelectedAssignmentReadRequest request,
      CanonicalGameplayRosterOwnerEvidence evidence,
      Class<? extends Throwable> expectedType,
      String expectedMessage) {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort = ownerPort();
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterSelectedAssignmentService service =
        new CanonicalGameplayRosterSelectedAssignmentService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest ownerRequest =
        new CanonicalGameplayRosterReadRequest(
            request.requestUuid(),
            request.canonicalAccountUuid(),
            request.expectedTarget(),
            request.playerExecutionContext());
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
