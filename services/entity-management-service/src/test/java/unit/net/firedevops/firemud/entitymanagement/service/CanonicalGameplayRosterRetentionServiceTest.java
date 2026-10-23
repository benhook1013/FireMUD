package net.firedevops.firemud.entitymanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterReferenceEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.Candidate;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.Health;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CanonicalGameplayRosterRetentionServiceTest {
  private final CanonicalGameplayRosterRetentionRepository repository =
      mock(CanonicalGameplayRosterRetentionRepository.class);
  private final GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient =
      mock(GameSessionCanonicalGameplayRosterOwnerReadClient.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private final CanonicalGameplayRosterRetentionPolicy policy =
      new CanonicalGameplayRosterRetentionPolicy(
          Duration.ofHours(8), Duration.ofHours(2), Duration.ofHours(8), Duration.ofMinutes(3), 2);
  private final CanonicalGameplayRosterRetentionService service =
      new CanonicalGameplayRosterRetentionService(
          repository, ownerClient, localTransactions(), policy, metrics);

  @Test
  void boundedPagesPassUnavailableAndUnknownOwnersThenDisposeOnlyRetiredCandidates() {
    var unavailable = candidate(1, "PROTECTED");
    var unknown = candidate(2, "PROTECTED");
    var eligible = candidate(3, "RETIRED");
    var held = candidate(4, "RETIRED");
    when(repository.candidates(null, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(unavailable, unknown);
            })
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of();
            });
    when(repository.candidates(unknown.binding().snapshot().snapshotUuid(), 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of(eligible, held);
            });
    when(repository.candidates(held.binding().snapshot().snapshotUuid(), 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return List.of();
            });
    when(ownerClient.getCanonicalGameplayRosterReference(unavailable.ownerRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              throw new IllegalStateException("Owner unavailable");
            });
    when(ownerClient.getCanonicalGameplayRosterReference(unknown.ownerRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return GetCanonicalGameplayRosterReferenceResponse.newBuilder()
                  .setStateValue(77)
                  .build();
            });
    when(repository.dispose(eligible))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.dispose(held))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return false;
            });
    when(repository.purgeDisposedTombstones(2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return 1;
            });
    when(repository.health())
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return new Health(91, 17);
            });

    service.sweep();
    verify(repository, never()).dispose(any());
    verify(repository, never()).observeRetirement(any(), any(), any());
    assertThat(outcomes("protected")).isEqualTo(2);
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(91);
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isEqualTo(17);
    service.sweep();
    service.sweep();
    org.mockito.Mockito.doReturn(new Health(0, 0)).when(repository).health();
    service.sweep();

    var pages = inOrder(repository);
    pages.verify(repository).candidates(null, 2);
    pages.verify(repository).candidates(unknown.binding().snapshot().snapshotUuid(), 2);
    pages.verify(repository).candidates(held.binding().snapshot().snapshotUuid(), 2);
    pages.verify(repository).candidates(null, 2);
    verify(repository, times(4)).purgeDisposedTombstones(2);
    verify(repository, never()).observeRetirement(any(), any(), any());
    verify(repository, never()).dispose(unavailable);
    verify(repository, never()).dispose(unknown);
    verify(ownerClient, times(2)).getCanonicalGameplayRosterReference(any());
    assertThat(outcomes("protected")).isEqualTo(3);
    assertThat(outcomes("disposed")).isEqualTo(1);
    assertThat(metrics.find("firemud.canonical.roster.cleanup").tag("result", "retired").counter())
        .isNull();
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isZero();
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isZero();
    assertOutsideTransaction();
  }

  @Test
  void positiveRetirementIsObservedLocallyAndDisposalWaitsForARetiredCandidatePage() {
    var protectedSnapshot = candidate(1, "PROTECTED");
    var refusedSnapshot = candidate(2, "PROTECTED");
    var proof = retirement(protectedSnapshot);
    var refusedProof = retirement(refusedSnapshot);
    var retiredSnapshot =
        new Candidate(
            protectedSnapshot.binding(),
            protectedSnapshot.ownerRequest(),
            2,
            "RETIRED",
            proof.getRetirementProofDigest());
    when(repository.candidates(null, 2)).thenReturn(List.of(protectedSnapshot, refusedSnapshot));
    when(repository.candidates(refusedSnapshot.binding().snapshot().snapshotUuid(), 2))
        .thenReturn(List.of(retiredSnapshot));
    when(ownerClient.getCanonicalGameplayRosterReference(protectedSnapshot.ownerRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return proof;
            });
    when(ownerClient.getCanonicalGameplayRosterReference(refusedSnapshot.ownerRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return refusedProof;
            });
    when(repository.observeRetirement(protectedSnapshot, proof, policy))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.observeRetirement(refusedSnapshot, refusedProof, policy))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return false;
            });
    when(repository.dispose(retiredSnapshot))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();
    verify(repository, never()).dispose(any());
    assertThat(outcomes("retired")).isEqualTo(1);
    assertThat(outcomes("protected")).isEqualTo(1);
    service.sweep();

    verify(repository).observeRetirement(protectedSnapshot, proof, policy);
    verify(repository).observeRetirement(refusedSnapshot, refusedProof, policy);
    verify(repository).dispose(retiredSnapshot);
    verify(repository, never()).dispose(protectedSnapshot);
    verify(repository, never()).dispose(refusedSnapshot);
    verify(ownerClient, times(2)).getCanonicalGameplayRosterReference(any());
    verify(repository, times(2)).purgeDisposedTombstones(eq(2));
    assertThat(outcomes("disposed")).isEqualTo(1);
  }

  @Test
  void nonterminalOwnerEvidenceNeverReachesRetirementOrDisposal() {
    var candidate = candidate(1, "PROTECTED");
    var reserved =
        CanonicalGameplayRosterReferenceEvidence.response(
            candidate.ownerRequest(), 1, new UUID(3, 1), null);
    when(repository.candidates(null, 2)).thenReturn(List.of(candidate));
    when(ownerClient.getCanonicalGameplayRosterReference(candidate.ownerRequest()))
        .thenReturn(reserved);
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();

    verify(repository, never()).observeRetirement(any(), any(), any());
    verify(repository, never()).dispose(any());
    assertThat(outcomes("protected")).isEqualTo(1);
  }

  @Test
  void refusesBothActualAndSynchronizationOnlyAmbientTransactionsBeforeAnyWork() {
    try {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      assertThatThrownBy(service::sweep)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_ROSTER_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.initSynchronization();
      assertThatThrownBy(service::sweep)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("CANONICAL_ROSTER_SWEEP_REQUIRES_NO_AMBIENT_TRANSACTION");
      verifyNoInteractions(repository, ownerClient);
    } finally {
      TransactionSynchronizationManager.clear();
    }
  }

  private double outcomes(String result) {
    return metrics.get("firemud.canonical.roster.cleanup").tag("result", result).counter().count();
  }

  private static Candidate candidate(long id, String state) {
    var target =
        new CanonicalGameplayRosterTarget(
            new UUID(1, 1),
            new UUID(1, 2),
            "earth",
            "main",
            new UUID(1, 3),
            7,
            11,
            13,
            new UUID(1, 4),
            "a".repeat(64),
            "release:exact",
            "b".repeat(64),
            "c".repeat(64),
            new UUID(1, 5),
            PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED,
            CanonicalGameplayRosterEntryPolicy.PRESEEDED_ONLY);
    var binding =
        new CanonicalGameplayRosterRetentionBinding(
            new UUID(1, 6),
            new CanonicalGameplayRosterSnapshotReference(new UUID(0, id), "d".repeat(64)),
            target);
    var request =
        CanonicalGameplayRosterReferenceEvidence.request(
            new UUID(2, id),
            binding.canonicalAccountUuid(),
            new UUID(1, 7),
            "gameplay",
            target.toWire());
    return new Candidate(
        binding, request, 1, state, "RETIRED".equals(state) ? "e".repeat(64) : null);
  }

  // Controlled owner evidence exercises wire qualification, not deployed producer authority.
  private static GetCanonicalGameplayRosterReferenceResponse retirement(Candidate candidate) {
    var receipt =
        CanonicalGameplayRosterResponse.newBuilder()
            .setCanonicalAccountUuid(candidate.binding().canonicalAccountUuid().toString())
            .setSnapshotUuid(candidate.binding().snapshot().snapshotUuid().toString())
            .setSnapshotDigest(candidate.binding().snapshot().snapshotDigest())
            .setTarget(candidate.binding().target().toWire())
            .build();
    return CanonicalGameplayRosterReferenceEvidence.sealRetirement(
        CanonicalGameplayRosterReferenceEvidence.response(
                candidate.ownerRequest(), 1, new UUID(3, 1), receipt)
            .toBuilder()
            .setTerminalOutcome("DENIED")
            .setState(
                CanonicalGameplayRosterReferenceState.CANONICAL_GAMEPLAY_ROSTER_REFERENCE_RETIRED)
            .setRetirementUuid(new UUID(3, 2).toString())
            .setSupersedingPointerDigest("f".repeat(64))
            .setSupersedingPointerVersion(12)
            .setSupersedingCatalogRevision(7)
            .setSupersedingWorldEpoch(13)
            .build());
  }

  // This callback seam checks orchestration boundaries; SQL commit/rollback and authenticated
  // owner issuance remain separate PostgreSQL/transport proof obligations.
  private static TransactionOperations localTransactions() {
    return new TransactionOperations() {
      @Override
      public <T> T execute(TransactionCallback<T> action) {
        assertOutsideTransaction();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
          return action.doInTransaction(new SimpleTransactionStatus());
        } finally {
          TransactionSynchronizationManager.clear();
        }
      }
    };
  }

  private static void assertSqlTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
  }

  private static void assertOutsideTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
  }
}
