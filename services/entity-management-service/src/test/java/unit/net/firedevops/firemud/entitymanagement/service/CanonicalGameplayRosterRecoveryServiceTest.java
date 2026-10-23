package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterEntryPolicy;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterTarget;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Controlled owner responses prove orchestration only; PostgreSQL and mTLS are separate proof. */
class CanonicalGameplayRosterRecoveryServiceTest {
  private final CanonicalGameplayRosterRetentionRepository repository =
      mock(CanonicalGameplayRosterRetentionRepository.class);
  private final GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient =
      mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
  private final CanonicalGameplayRosterRetentionPolicy policy =
      new CanonicalGameplayRosterRetentionPolicy(
          Duration.ofHours(2), Duration.ofHours(1), Duration.ofHours(2), Duration.ofMinutes(1), 2);

  @Test
  void independentlyReadsExactOwnerOutsideSqlThenClosesInOneLocalTransaction() {
    var request = request();
    var owner =
        CanonicalGameplayRosterReferenceEvidence.response(
                request.getOriginalSelection(), 1, UUID.randomUUID(), null)
            .toBuilder()
            .setRecoveryUuid(request.getRecoveryUuid())
            .build();
    var response =
        CanonicalGameplayRosterRecoveryEvidence.response(
            request,
            UUID.randomUUID(),
            owner.getOwnerFence(),
            UUID.fromString(owner.getTargetFenceUuid()),
            null);
    when(ownerClient.getCanonicalGameplayRosterReference(request.getOriginalSelection()))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
              return owner;
            });
    when(repository.closeAdmission(request, owner, policy))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
              return response;
            });
    var service =
        new CanonicalGameplayRosterRecoveryService(
            repository, ownerClient, localTransactions(), policy);
    assertThat(service.recover(request)).isEqualTo(response);
    verify(repository).closeAdmission(request, owner, policy);
  }

  @Test
  void unavailableOrMismatchedRecoveryCannotOpenLocalTransaction() {
    var request = request();
    var transactions = mock(TransactionOperations.class);
    var service =
        new CanonicalGameplayRosterRecoveryService(repository, ownerClient, transactions, policy);
    when(ownerClient.getCanonicalGameplayRosterReference(request.getOriginalSelection()))
        .thenThrow(new IllegalStateException("Owner unavailable"));
    assertThatThrownBy(() -> service.recover(request)).isInstanceOf(IllegalStateException.class);
    var owner =
        CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(), 1, UUID.randomUUID(), null);
    doReturn(owner)
        .when(ownerClient)
        .getCanonicalGameplayRosterReference(request.getOriginalSelection());
    assertThatThrownBy(() -> service.recover(request)).isInstanceOf(IllegalArgumentException.class);
    when(ownerClient.getCanonicalGameplayRosterReference(request.getOriginalSelection()))
        .thenReturn(owner.toBuilder().setRecoveryUuid(UUID.randomUUID().toString()).build());
    assertThatThrownBy(() -> service.recover(request)).isInstanceOf(IllegalArgumentException.class);
    var otherOriginal =
        request.getOriginalSelection().toBuilder()
            .setSessionUuid(UUID.randomUUID().toString())
            .build();
    when(ownerClient.getCanonicalGameplayRosterReference(request.getOriginalSelection()))
        .thenReturn(
            owner.toBuilder()
                .setRequest(otherOriginal)
                .setRecoveryUuid(request.getRecoveryUuid())
                .build());
    assertThatThrownBy(() -> service.recover(request)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(repository, transactions);
  }

  @Test
  void invalidRequestAndAmbientTransactionsFailBeforeOwnerOrLocalWork() {
    var transactions = mock(TransactionOperations.class);
    var service =
        new CanonicalGameplayRosterRecoveryService(repository, ownerClient, transactions, policy);
    assertThatThrownBy(
            () -> service.recover(CanonicalGameplayRosterRecoveryRequest.getDefaultInstance()))
        .isInstanceOf(IllegalArgumentException.class);
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(() -> service.recover(request()))
          .isInstanceOf(IllegalStateException.class);
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(() -> service.recover(request()))
          .isInstanceOf(IllegalStateException.class);
      verifyNoInteractions(repository, ownerClient, transactions);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private static TransactionOperations localTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> callback) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
          return callback.doInTransaction(new SimpleTransactionStatus());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    };
  }

  private static CanonicalGameplayRosterRecoveryRequest request() {
    var target =
        CanonicalGameplayRosterTarget.newBuilder()
            .setTenantUuid(UUID.randomUUID().toString())
            .setRealmUuid(UUID.randomUUID().toString())
            .setGameInstanceUuid(UUID.randomUUID().toString())
            .setCanonicalVersionUuid(UUID.randomUUID().toString())
            .setPlayableStateNamespaceUuid(UUID.randomUUID().toString())
            .setWorldSlug("world")
            .setRealmSlug("realm")
            .setCatalogRevision(1)
            .setPointerVersion(1)
            .setActiveWorldEpoch(1)
            .setPublishedPolicyDigest("a".repeat(64))
            .setPublishedOwnerProofDigest("b".repeat(64))
            .setAdmissionPointerSnapshotDigest("c".repeat(64))
            .setPublishedReleaseBundleRef("release:exact")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setEntryPolicy(
                CanonicalGameplayRosterEntryPolicy.CANONICAL_ROSTER_ENTRY_POLICY_PRESEEDED_ONLY)
            .build();
    return CanonicalGameplayRosterRecoveryRequest.newBuilder()
        .setSchemaVersion(1)
        .setRecoveryUuid(UUID.randomUUID().toString())
        .setOriginalSelection(
            CanonicalGameplayRosterReferenceEvidence.request(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "gameplay", target))
        .build();
  }
}
