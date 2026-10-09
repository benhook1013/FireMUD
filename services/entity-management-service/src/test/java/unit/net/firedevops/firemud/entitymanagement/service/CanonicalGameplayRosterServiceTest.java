package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.entitymanagement.repository.CharacterRepository;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayRosterServiceTest {
  @Test
  void exactOwnerResolvedPreseededTargetReadsPersistedSnapshot() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest request = request(target());
    CanonicalGameplayRosterOwnerEvidence evidence = evidence(request, request.expectedTarget());
    CanonicalGameplayRosterSnapshot expected =
        new CanonicalGameplayRosterSnapshot(
            request.canonicalAccountUuid(),
            UUID.randomUUID(),
            "a".repeat(64),
            request.expectedTarget(),
            List.of(new CanonicalGameplayRosterActor(UUID.randomUUID(), "Persisted Actor")));
    when(ownerPort.resolveCurrentTarget(request)).thenReturn(evidence);
    when(repository.captureCanonicalGameplayRosterSnapshot(request, evidence)).thenReturn(expected);

    assertThat(service.read(request)).isEqualTo(expected);

    verify(ownerPort).resolveCurrentTarget(request);
    verify(repository).captureCanonicalGameplayRosterSnapshot(request, evidence);
  }

  @Test
  void unsupportedEntryPoliciesFailBeforeOwnerReadsOrRosterLookup() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);

    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        withPolicy(target(), CanonicalGameplayRosterEntryPolicy.PLAYER_CREATED))))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_ENTRY_POLICY_UNSUPPORTED");
    assertThatThrownBy(
            () ->
                service.read(
                    request(
                        withPolicy(target(), CanonicalGameplayRosterEntryPolicy.AUTO_PROVISIONED))))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                service.read(
                    request(withPolicy(target(), CanonicalGameplayRosterEntryPolicy.UNSPECIFIED))))
        .isInstanceOf(UnsupportedOperationException.class);

    verifyNoInteractions(ownerPort, repository);
  }

  @Test
  void independentlyResolvedCrossNamespaceTargetIsRejectedBeforeRepositoryRead() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest request = request(target());
    CanonicalGameplayRosterTarget differentNamespace =
        new CanonicalGameplayRosterTarget(
            request.expectedTarget().tenantUuid(),
            request.expectedTarget().realmUuid(),
            request.expectedTarget().worldSlug(),
            request.expectedTarget().realmSlug(),
            request.expectedTarget().gameInstanceUuid(),
            request.expectedTarget().catalogRevision(),
            request.expectedTarget().pointerVersion(),
            request.expectedTarget().activeWorldEpoch(),
            request.expectedTarget().canonicalVersionUuid(),
            request.expectedTarget().publishedPolicyDigest(),
            request.expectedTarget().publishedReleaseBundleRef(),
            request.expectedTarget().admissionPointerSnapshotDigest(),
            request.expectedTarget().publishedOwnerProofDigest(),
            UUID.randomUUID(),
            request.expectedTarget().playableStateScope(),
            request.expectedTarget().entryPolicy());
    when(ownerPort.resolveCurrentTarget(request)).thenReturn(evidence(request, differentNamespace));

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_TARGET_MISMATCH");

    verify(ownerPort).resolveCurrentTarget(request);
    verifyNoInteractions(repository);
  }

  @Test
  void independentlyResolvedChangedOwnerCountersAreRejectedBeforeRepositoryRead() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest request = request(target());
    CanonicalGameplayRosterTarget changedPointer =
        withCounters(
            request.expectedTarget(),
            request.expectedTarget().pointerVersion() + 1,
            request.expectedTarget().activeWorldEpoch());
    when(ownerPort.resolveCurrentTarget(request)).thenReturn(evidence(request, changedPointer));

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_TARGET_MISMATCH");

    verify(ownerPort).resolveCurrentTarget(request);
    verifyNoInteractions(repository);
  }

  @Test
  void independentlyResolvedChangedActiveWorldEpochIsRejectedBeforeRepositoryRead() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest request = request(target());
    CanonicalGameplayRosterTarget changedEpoch =
        withCounters(
            request.expectedTarget(),
            request.expectedTarget().pointerVersion(),
            request.expectedTarget().activeWorldEpoch() + 1);
    when(ownerPort.resolveCurrentTarget(request)).thenReturn(evidence(request, changedEpoch));

    assertThatThrownBy(() -> service.read(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_TARGET_MISMATCH");

    verify(ownerPort).resolveCurrentTarget(request);
    verifyNoInteractions(repository);
  }

  @Test
  void canonicalRosterDigestBindsPointerVersionAndActiveWorldEpoch() {
    UUID accountUuid = UUID.randomUUID();
    CanonicalGameplayRosterTarget exactTarget = target();
    List<CanonicalGameplayRosterActor> actors =
        List.of(new CanonicalGameplayRosterActor(UUID.randomUUID(), "Actor"));

    String exactDigest =
        CanonicalGameplayRosterSnapshotDigest.compute(accountUuid, exactTarget, actors);
    String changedPointerDigest =
        CanonicalGameplayRosterSnapshotDigest.compute(
            accountUuid,
            withCounters(
                exactTarget, exactTarget.pointerVersion() + 1, exactTarget.activeWorldEpoch()),
            actors);
    String changedEpochDigest =
        CanonicalGameplayRosterSnapshotDigest.compute(
            accountUuid,
            withCounters(
                exactTarget, exactTarget.pointerVersion(), exactTarget.activeWorldEpoch() + 1),
            actors);

    assertThat(changedPointerDigest).isNotEqualTo(exactDigest);
    assertThat(changedEpochDigest).isNotEqualTo(exactDigest);
    assertThat(changedEpochDigest).isNotEqualTo(changedPointerDigest);
  }

  @Test
  void missingCurrentOwnerProducerFailsClosedWithoutRepositoryLookup() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        request -> {
          throw new CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException();
        };
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);

    assertThatThrownBy(() -> service.read(request(target())))
        .isInstanceOf(
            CanonicalGameplayRosterOwnerEvidencePort.OwnerEvidenceUnavailableException.class);

    verifyNoInteractions(repository);
  }

  @Test
  void ownerReadCannotRunInsideAmbientTransactionOrSynchronization() {
    CanonicalGameplayRosterOwnerEvidencePort ownerPort =
        mock(CanonicalGameplayRosterOwnerEvidencePort.class);
    CharacterRepository repository = mock(CharacterRepository.class);
    CanonicalGameplayRosterService service =
        new CanonicalGameplayRosterService(ownerPort, repository);
    CanonicalGameplayRosterReadRequest request = request(target());

    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    } finally {
      TransactionSynchronizationManager.clear();
    }

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThatThrownBy(() -> service.read(request))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_GAMEPLAY_ROSTER_OWNER_READ_REQUIRES_NO_AMBIENT_TRANSACTION");
    } finally {
      TransactionSynchronizationManager.clear();
    }
    verifyNoInteractions(ownerPort, repository);
  }

  private static CanonicalGameplayRosterReadRequest request(CanonicalGameplayRosterTarget target) {
    UUID requestUuid = UUID.randomUUID();
    UUID accountUuid = UUID.randomUUID();
    return new CanonicalGameplayRosterReadRequest(
        requestUuid,
        accountUuid,
        target,
        new CanonicalGameplayRosterExecutionContext(
            accountUuid,
            target.tenantUuid(),
            target.playableStateNamespaceId(),
            target.gameInstanceUuid(),
            null,
            UUID.randomUUID(),
            target.realmUuid(),
            requestUuid,
            target.playableStateScope()));
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
        "3".repeat(64),
        UUID.randomUUID(),
        PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
        CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
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

  private static CanonicalGameplayRosterTarget withCounters(
      CanonicalGameplayRosterTarget target, long pointerVersion, long activeWorldEpoch) {
    return new CanonicalGameplayRosterTarget(
        target.tenantUuid(),
        target.realmUuid(),
        target.worldSlug(),
        target.realmSlug(),
        target.gameInstanceUuid(),
        target.catalogRevision(),
        pointerVersion,
        activeWorldEpoch,
        target.canonicalVersionUuid(),
        target.publishedPolicyDigest(),
        target.publishedReleaseBundleRef(),
        target.admissionPointerSnapshotDigest(),
        target.publishedOwnerProofDigest(),
        target.playableStateNamespaceId(),
        target.playableStateScope(),
        target.entryPolicy());
  }
}
