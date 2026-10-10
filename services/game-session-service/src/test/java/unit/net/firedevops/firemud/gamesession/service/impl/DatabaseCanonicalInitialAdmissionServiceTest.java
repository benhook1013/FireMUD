package net.firedevops.firemud.gamesession.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionOwnerProof.Outcome;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionRequest.OriginKind;
import net.firedevops.firemud.gamesession.dto.CanonicalInitialAdmissionWorldProof;
import net.firedevops.firemud.gamesession.repository.CanonicalInitialAdmissionRepository;
import net.firedevops.firemud.gamesession.repository.GameSessionCanonicalAdmissionPointerRepository;
import net.firedevops.firemud.gamesession.service.CanonicalInitialAdmissionWorldVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DatabaseCanonicalInitialAdmissionServiceTest {
  private final CanonicalInitialAdmissionRepository repository =
      mock(CanonicalInitialAdmissionRepository.class);
  private final GameSessionCanonicalAdmissionPointerRepository pointerRepository =
      mock(GameSessionCanonicalAdmissionPointerRepository.class);
  private final CanonicalInitialAdmissionWorldVerifier worldVerifier =
      mock(CanonicalInitialAdmissionWorldVerifier.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager(0);
  private int readbackCount;
  private final DatabaseCanonicalInitialAdmissionService service =
      new DatabaseCanonicalInitialAdmissionService(
          repository, pointerRepository, worldVerifier, transactionManager);

  @AfterEach
  void clearTransaction() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void bindUsesWritableReadCommittedTransactionsAndKeepsRemoteAndReadbackOutside() {
    CanonicalInitialAdmissionRequest request = request();
    CanonicalInitialAdmissionOwnerProof pending = pendingProof(request);
    CanonicalInitialAdmissionOwnerProof committed = committedProof(request);
    CanonicalInitialAdmissionWorldProof worldProof = worldProof(request);

    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .reserve(request);
    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .commit(request, worldProof, null);
    when(repository.read(request.targetNamespace(), request.initialAdmissionRequestId()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return Optional.of(readbackCount++ == 0 ? pending : committed);
            });
    when(worldVerifier.verify(request))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return worldProof;
            });

    assertThat(service.bind(request)).isEqualTo(committed);

    verify(repository).reserve(request);
    verify(repository).commit(request, worldProof, null);
    verify(repository, times(2))
        .read(request.targetNamespace(), request.initialAdmissionRequestId());
    verify(worldVerifier).verify(request);
    verifyNoInteractions(pointerRepository);
    assertThat(transactionManager.settings)
        .containsExactly(writableReadCommittedRequiresNew(), writableReadCommittedRequiresNew());
    assertThat(transactionManager.commitCount).isEqualTo(2);
    assertThat(transactionManager.rollbackCount).isZero();
  }

  @Test
  void abortUsesWritableReadCommittedTransactionsAndReadsTerminalProofAfterCommit() {
    CanonicalInitialAdmissionRequest request = request();
    CanonicalInitialAdmissionOwnerProof pending = pendingProof(request);
    CanonicalInitialAdmissionOwnerProof aborted = abortedProof(request);

    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .reserve(request);
    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .abort(request, null, "test abort");
    when(repository.read(request.targetNamespace(), request.initialAdmissionRequestId()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return Optional.of(readbackCount++ == 0 ? pending : aborted);
            });

    assertThat(service.abort(request, "test abort")).isEqualTo(aborted);

    verify(repository).reserve(request);
    verify(repository).abort(request, null, "test abort");
    verify(repository, times(2))
        .read(request.targetNamespace(), request.initialAdmissionRequestId());
    verifyNoInteractions(pointerRepository, worldVerifier);
    assertThat(transactionManager.settings)
        .containsExactly(writableReadCommittedRequiresNew(), writableReadCommittedRequiresNew());
    assertThat(transactionManager.commitCount).isEqualTo(2);
    assertThat(transactionManager.rollbackCount).isZero();
  }

  @Test
  void bindDoesNotReturnSuccessWhenOwnerCommitFails() {
    transactionManager.failOnCommitNumber = 2;
    CanonicalInitialAdmissionRequest request = request();
    CanonicalInitialAdmissionOwnerProof pending = pendingProof(request);
    CanonicalInitialAdmissionWorldProof worldProof = worldProof(request);

    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .reserve(request);
    doAnswer(
            invocation -> {
              assertWritableReadCommittedTransaction();
              return null;
            })
        .when(repository)
        .commit(request, worldProof, null);
    when(repository.read(request.targetNamespace(), request.initialAdmissionRequestId()))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return Optional.of(pending);
            });
    when(worldVerifier.verify(request))
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return worldProof;
            });

    assertThatThrownBy(() -> service.bind(request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated transaction commit failure");

    verify(repository).reserve(request);
    verify(repository).commit(request, worldProof, null);
    verify(repository, times(1))
        .read(request.targetNamespace(), request.initialAdmissionRequestId());
    verify(worldVerifier).verify(request);
    assertThat(transactionManager.commitCount).isEqualTo(2);
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
        .thenAnswer(
            invocation -> {
              assertOutsideTransaction();
              return Optional.of(committed);
            });

    assertThat(service.bind(request)).isEqualTo(committed);

    verify(repository).reserve(request);
    verify(repository).read(request.targetNamespace(), request.initialAdmissionRequestId());
    verifyNoInteractions(pointerRepository, worldVerifier);
    assertThat(transactionManager.settings).containsExactly(writableReadCommittedRequiresNew());
  }

  private void assertAmbientTransactionRejected(boolean readOnly, Runnable operation) {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);

    assertThatThrownBy(operation::run)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ambient transaction");

    verifyNoInteractions(repository, pointerRepository, worldVerifier);
    assertThat(transactionManager.settings).isEmpty();
  }

  private static void assertWritableReadCommittedTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  private static void assertOutsideTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }

  private static TransactionSettings writableReadCommittedRequiresNew() {
    return new TransactionSettings(
        TransactionDefinition.PROPAGATION_REQUIRES_NEW,
        TransactionDefinition.ISOLATION_READ_COMMITTED,
        false);
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

  private static CanonicalInitialAdmissionOwnerProof pendingProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionOwnerProof(
        Outcome.PENDING,
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
        null,
        null,
        null,
        false,
        null);
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

  private static CanonicalInitialAdmissionOwnerProof abortedProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionOwnerProof(
        Outcome.ABORTED,
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
        null,
        null,
        "sha256:" + "c".repeat(64),
        true,
        Instant.parse("2026-10-10T00:00:00Z"));
  }

  private static CanonicalInitialAdmissionWorldProof worldProof(
      CanonicalInitialAdmissionRequest request) {
    return new CanonicalInitialAdmissionWorldProof(
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
        "ACTIVE",
        request.activeLifecycleEpoch(),
        request.originKind(),
        request.expectedCatalogRevision(),
        request.expectedPriorPointerVersion(),
        request.holdId(),
        request.holdFence(),
        request.holdBindingDigest());
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private record TransactionSettings(int propagation, int isolation, boolean readOnly) {}

  private static final class RecordingTransactionManager implements PlatformTransactionManager {
    private final List<TransactionSettings> settings = new ArrayList<>();
    private int commitCount;
    private int rollbackCount;
    private int failOnCommitNumber;

    private RecordingTransactionManager(int failOnCommitNumber) {
      this.failOnCommitNumber = failOnCommitNumber;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      settings.add(
          new TransactionSettings(
              definition.getPropagationBehavior(),
              definition.getIsolationLevel(),
              definition.isReadOnly()));
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      commitCount++;
      TransactionSynchronizationManager.clear();
      if (commitCount == failOnCommitNumber) {
        throw new IllegalStateException("simulated transaction commit failure");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      rollbackCount++;
      TransactionSynchronizationManager.clear();
    }
  }
}
