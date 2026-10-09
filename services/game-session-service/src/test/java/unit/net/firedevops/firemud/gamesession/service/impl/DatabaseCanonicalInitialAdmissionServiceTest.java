package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DatabaseCanonicalInitialAdmissionServiceTest {
  private final CanonicalInitialAdmissionRepository repository =
      mock(CanonicalInitialAdmissionRepository.class);
  private final GameSessionCanonicalAdmissionPointerRepository pointerRepository =
      mock(GameSessionCanonicalAdmissionPointerRepository.class);
  private final CanonicalInitialAdmissionWorldVerifier worldVerifier =
      mock(CanonicalInitialAdmissionWorldVerifier.class);
  private final DatabaseCanonicalInitialAdmissionService service =
      new DatabaseCanonicalInitialAdmissionService(repository, pointerRepository, worldVerifier);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void bindRejectsWritableAmbientTransactionBeforeAnyCollaboratorAccess() {
    assertAmbientTransactionRejected(false, () -> service.bind(request()));
  }

  @Test
  void bindRejectsReadOnlyAmbientTransactionBeforeAnyCollaboratorAccess() {
    assertAmbientTransactionRejected(true, () -> service.bind(request()));
  }

  @Test
  void abortRejectsWritableAmbientTransactionBeforeAnyCollaboratorAccess() {
    assertAmbientTransactionRejected(false, () -> service.abort(request(), "test abort"));
  }

  @Test
  void abortRejectsReadOnlyAmbientTransactionBeforeAnyCollaboratorAccess() {
    assertAmbientTransactionRejected(true, () -> service.abort(request(), "test abort"));
  }

  @Test
  void committedReplayReturnsStoredOwnerProofWithoutWorldVerification() {
    CanonicalInitialAdmissionRequest request = request();
    CanonicalInitialAdmissionOwnerProof committed = committedProof(request);
    when(repository.read(request.targetNamespace(), request.initialAdmissionRequestId()))
        .thenReturn(Optional.of(committed));

    assertThat(service.bind(request)).isEqualTo(committed);

    verify(repository).reserve(request);
    verify(repository).read(request.targetNamespace(), request.initialAdmissionRequestId());
    verifyNoInteractions(pointerRepository, worldVerifier);
  }

  private void assertAmbientTransactionRejected(boolean readOnly, Runnable operation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);

    assertThatThrownBy(operation::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");

    verifyNoInteractions(repository, pointerRepository, worldVerifier);
  }

  private static CanonicalInitialAdmissionRequest request() {
    String targetNamespace = "initial-admission-test";
    UUID tenantId = uuid("11111111-1111-4111-8111-111111111111");
    String worldSlug = "demo-world";
    UUID realmId = uuid("22222222-2222-4222-8222-222222222222");
    UUID playableStateNamespaceId = uuid("33333333-3333-4333-8333-333333333333");
    String playableStateScope = "SHARED";
    UUID gameInstanceId = uuid("44444444-4444-4444-8444-444444444444");
    UUID versionId = uuid("55555555-5555-4555-8555-555555555555");
    long activeLifecycleEpoch = 6L;
    long catalogRevision = 7L;
    OriginKind originKind = OriginKind.NO_PRIOR_POINTER;
    String requestId = "initial-admission-replay-1";
    String requestDigest =
        CanonicalInitialAdmissionRequest.computeRequestDigest(
            targetNamespace,
            tenantId,
            worldSlug,
            realmId,
            playableStateNamespaceId,
            playableStateScope,
            gameInstanceId,
            versionId,
            activeLifecycleEpoch,
            catalogRevision,
            originKind,
            null,
            requestId);

    return new CanonicalInitialAdmissionRequest(
        targetNamespace,
        tenantId,
        worldSlug,
        realmId,
        playableStateNamespaceId,
        playableStateScope,
        gameInstanceId,
        versionId,
        activeLifecycleEpoch,
        catalogRevision,
        originKind,
        null,
        requestId,
        requestDigest,
        uuid("66666666-6666-4666-8666-666666666666"),
        uuid("77777777-7777-4777-8777-777777777777"),
        "sha256:" + "a".repeat(64));
  }

  private static CanonicalInitialAdmissionOwnerProof committedProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionOwnerProof(
        Outcome.COMMITTED,
        request.initialAdmissionRequestId(),
        request.requestDigest(),
        request.targetNamespace(),
        request.canonicalTenantId(),
        request.worldSlug(),
        request.realmId(),
        request.playableStateNamespaceId(),
        request.playableStateScope(),
        request.canonicalGameInstanceId(),
        request.canonicalVersionId(),
        request.activeLifecycleEpoch(),
        request.expectedCatalogRevision(),
        request.originKind(),
        request.expectedPriorPointerVersion(),
        request.holdId(),
        request.holdFence(),
        request.holdBindingDigest(),
        1L,
        1L,
        "sha256:" + "b".repeat(64),
        false,
        Instant.parse("2026-10-10T00:00:00Z"));
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }
}
