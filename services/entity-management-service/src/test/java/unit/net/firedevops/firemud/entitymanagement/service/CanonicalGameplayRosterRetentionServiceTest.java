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
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRetirementEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.Candidate;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.Health;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository.RetirementTransfer;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.v1.CanonicalGameplayRosterReferenceState;
import net.firedevops.firemud.gamesession.v1.GetCanonicalGameplayRosterReferenceResponse;
import org.junit.jupiter.api.BeforeEach;
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

  @BeforeEach
  void currentRetiredFixturesAlreadyHaveQualifiedSeals() {
    when(repository.initializeRetainedRetirement(any()))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
  }

  @Test
  void invalidKeyedItemsInEveryStageAdvanceWithoutOwnerCallsOrMutations() {
    var badSnapshot = Candidate.invalid(new UUID(0, 1));
    var snapshot = candidate(2, "RETIRED");
    var badClosure = ClosedAdmissionCandidate.invalid(new UUID(2, 1), new UUID(5, 1));
    var closure = closure(2);
    var badTransfer = RetirementTransfer.invalid(new UUID(8, 1), "PENDING_ACK");
    var transfer = transfer(2);
    when(repository.candidates(null, 2)).thenReturn(List.of(badSnapshot, snapshot));
    when(repository.closedAdmissionCandidates(null, 2)).thenReturn(List.of(badClosure, closure));
    when(repository.retirementTransfers(null, 2)).thenReturn(List.of(badTransfer, transfer));
    when(repository.health()).thenReturn(new Health(0, 0));
    when(repository.dispose(snapshot)).thenReturn(true);
    when(ownerClient.getCanonicalGameplayRosterReference(
            closure.response().getRequest().getOriginalSelection()))
        .thenReturn(closureOwner(closure));
    when(ownerClient.acknowledgeCanonicalGameplayRosterRetirement(
            transfer.acknowledgementRequest()))
        .thenReturn(
            CanonicalGameplayRosterRetirementEvidence.response(
                transfer.acknowledgementRequest(), UUID.randomUUID(), 1000));

    service.sweep();
    assertThat(outcomes("failure")).isEqualTo(3);
    verify(repository).dispose(snapshot);
    verify(repository).purgeClosedAdmission(eq(closure), any());
    verify(repository).recordRetirementAcknowledgement(eq(transfer), any());
    verify(repository, never()).initializeRetainedRetirement(badSnapshot);
    verify(repository, never()).purgeClosedAdmission(eq(badClosure), any());
    verify(repository, never()).recordRetirementAcknowledgement(eq(badTransfer), any());
    verify(ownerClient, times(1)).getCanonicalGameplayRosterReference(any());
    verify(ownerClient, times(1)).acknowledgeCanonicalGameplayRosterRetirement(any());

    service.sweep();
    verify(repository).candidates(snapshot.snapshotUuid(), 2);
    verify(repository).closedAdmissionCandidates(closure.operationUuid(), 2);
    verify(repository).retirementTransfers(transfer.observationUuid(), 2);
    assertThat(outcomes("failure")).isEqualTo(3);
  }

  @Test
  void legacyInitializationRefusalKeepsPayloadAndDoesNotReadUnavailableOwner() {
    var retained = candidate(1, "RETIRED");
    when(repository.candidates(null, 2)).thenReturn(List.of(retained));
    org.mockito.Mockito.doReturn(false).when(repository).initializeRetainedRetirement(retained);
    when(repository.health()).thenReturn(new Health(0, 0));
    service.sweep();
    verify(repository).classifyRetainedDependencies(2);
    verify(repository).initializeRetainedRetirement(retained);
    verify(repository, never()).dispose(any());
    verify(repository, never()).prepareRetirement(any(), org.mockito.ArgumentMatchers.anyInt());
    verify(repository).purgeTargetClassifications(2);
    verifyNoInteractions(ownerClient);
    assertThat(outcomes("protected")).isEqualTo(1);
  }

  @Test
  void boundedPreparationFailureDoesNotBlockNextSnapshotOrRequireOwnerAvailability() {
    var failing = candidate(1, "RETIRED");
    var advancing = candidate(2, "RETIRED");
    when(repository.candidates(null, 2)).thenReturn(List.of(failing, advancing));
    when(repository.health()).thenReturn(new Health(0, 0));
    when(repository.prepareRetirement(failing, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              throw new IllegalStateException("original page unavailable");
            });
    when(repository.prepareRetirement(advancing, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return 2;
            });
    service.sweep();
    verify(repository).prepareRetirement(failing, 2);
    verify(repository).prepareRetirement(advancing, 2);
    verify(repository).purgeRetiredTargets(2);
    verifyNoInteractions(ownerClient);
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(outcomes("protected")).isEqualTo(1);
  }

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
    assertThat(outcomes("failure")).isEqualTo(2);
    assertThat(outcomes("protected")).isZero();
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
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(2);
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
    assertThat(outcomes("failure")).isZero();
  }

  @Test
  void failedClosedAdmissionOwnerReadHasAFailureOutcomeDistinctFromProtection() {
    var original = candidate(1, "PROTECTED").ownerRequest();
    var request =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(new UUID(4, 1).toString())
            .build();
    var response =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence.response(
            request, new UUID(4, 2), 1, new UUID(3, 1), null);
    var closure =
        new CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate(
            UUID.fromString(original.getOperationUuid()), new UUID(4, 2), response);
    when(repository.candidates(null, 2)).thenReturn(List.of());
    when(repository.closedAdmissionCandidates(null, 2)).thenReturn(List.of(closure));
    when(ownerClient.getCanonicalGameplayRosterReference(original))
        .thenThrow(new IllegalStateException("Owner unavailable"));
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();

    verify(repository, never()).purgeClosedAdmission(any(), any());
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(outcomes("protected")).isZero();
  }

  @Test
  void closedAdmissionsUseIndependentOwnerReadsOutsideSqlAndABoundedSeparateCursor() {
    var original = candidate(1, "PROTECTED").ownerRequest();
    var request =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(new UUID(4, 1).toString())
            .build();
    var response =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence.response(
            request, new UUID(4, 2), 1, new UUID(3, 1), null);
    var closure =
        new CanonicalGameplayRosterRetentionRepository.ClosedAdmissionCandidate(
            UUID.fromString(original.getOperationUuid()), new UUID(4, 2), response);
    var owner =
        CanonicalGameplayRosterReferenceEvidence.response(original, 1, new UUID(3, 1), null)
            .toBuilder()
            .setRecoveryUuid(request.getRecoveryUuid())
            .build();
    when(repository.candidates(null, 2)).thenReturn(List.of());
    when(repository.closedAdmissionCandidates(null, 2)).thenReturn(List.of(closure));
    when(repository.closedAdmissionCandidates(closure.operationUuid(), 2)).thenReturn(List.of());
    when(ownerClient.getCanonicalGameplayRosterReference(original))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return owner;
            });
    when(repository.purgeClosedAdmission(closure, owner))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return false;
            });
    when(repository.purgeOrphanOpenAdmissions(policy, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return 0;
            });
    when(repository.health()).thenReturn(new Health(0, 0));
    service.sweep();
    service.sweep();
    verify(repository).closedAdmissionCandidates(null, 2);
    verify(repository).closedAdmissionCandidates(closure.operationUuid(), 2);
    verify(repository).purgeClosedAdmission(closure, owner);
    verify(repository, times(2)).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository, never()).observeRetirement(any(), any(), any());
    verify(repository, never()).dispose(any());
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isZero();
  }

  @Test
  void failedSnapshotPageStillProcessesClosedAdmissionsPurgesAndHealth() {
    var closure = closure(1);
    var owner = closureOwner(closure);
    when(repository.candidates(null, 2))
        .thenThrow(new org.jooq.exception.DataAccessException("snapshot mapping failed"));
    when(repository.closedAdmissionCandidates(null, 2)).thenReturn(List.of(closure));
    when(ownerClient.getCanonicalGameplayRosterReference(owner.getRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return owner;
            });
    when(repository.purgeClosedAdmission(closure, owner)).thenReturn(true);
    when(repository.health()).thenReturn(new Health(11, 3));

    service.sweep();

    verify(repository).purgeClosedAdmission(closure, owner);
    assertPurgeAndHealthStagesRan();
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(outcomes("admission_purged")).isEqualTo(1);
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(11);
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isEqualTo(3);
  }

  @Test
  void failedClosedAdmissionPageStillRunsPurgesAndHealthAndPreservesCandidateCounts() {
    var snapshot = candidate(1, "RETIRED");
    when(repository.candidates(null, 2)).thenReturn(List.of(snapshot));
    when(repository.dispose(snapshot)).thenReturn(true);
    when(repository.closedAdmissionCandidates(null, 2))
        .thenThrow(new org.jooq.exception.DataAccessException("closure mapping failed"));
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();

    verify(repository).dispose(snapshot);
    assertPurgeAndHealthStagesRan();
    assertThat(outcomes("disposed")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(1);
  }

  @Test
  void failedOrphanPurgeStillRunsDisposedPurgeAndHealth() {
    when(repository.purgeOrphanOpenAdmissions(policy, 2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              throw new org.jooq.exception.DataAccessException("orphan purge unavailable");
            });
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();

    assertPurgeAndHealthStagesRan();
    assertThat(outcomes("failure")).isEqualTo(1);
  }

  @Test
  void failedDisposedPurgeStillRefreshesHealth() {
    when(repository.purgeDisposedTombstones(2))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              throw new org.jooq.exception.DataAccessException("disposed purge unavailable");
            });
    when(repository.health()).thenReturn(new Health(4, 2));

    service.sweep();

    assertPurgeAndHealthStagesRan();
    assertThat(outcomes("failure")).isEqualTo(1);
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(4);
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isEqualTo(2);
  }

  @Test
  void unavailableHealthPublishesUnknownInsteadOfStaleValuesThenRecoversActualValues() {
    when(repository.health())
        .thenReturn(new Health(91, 17))
        .thenThrow(new org.jooq.exception.DataAccessException("health unavailable"))
        .thenReturn(new Health(7, 2));

    service.sweep();
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(91);
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isEqualTo(17);
    service.sweep();
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isNaN();
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value()).isNaN();
    service.sweep();

    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(7);
    assertThat(metrics.get("firemud.canonical.roster.cleanup_lag_seconds").gauge().value())
        .isEqualTo(2);
    verify(repository, times(3)).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository, times(3)).purgeDisposedTombstones(2);
    verify(repository, times(3)).health();
    assertThat(outcomes("failure")).isEqualTo(1);
    assertOutsideTransaction();
  }

  @Test
  void failedSnapshotPageRetriesTheSameCursorUntilAValidEmptyPageResetsIt() {
    var snapshot = candidate(1, "RETIRED");
    UUID after = snapshot.binding().snapshot().snapshotUuid();
    when(repository.candidates(null, 2)).thenReturn(List.of(snapshot)).thenReturn(List.of());
    when(repository.candidates(after, 2))
        .thenThrow(new org.jooq.exception.DataAccessException("snapshot page unavailable"))
        .thenReturn(List.of());
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();
    service.sweep();
    service.sweep();
    service.sweep();

    var pages = inOrder(repository);
    pages.verify(repository).candidates(null, 2);
    pages.verify(repository).candidates(after, 2);
    pages.verify(repository).candidates(after, 2);
    pages.verify(repository).candidates(null, 2);
    verify(repository, times(4)).closedAdmissionCandidates(null, 2);
    verify(repository, times(4)).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository, times(4)).purgeDisposedTombstones(2);
    verify(repository, times(4)).health();
    assertThat(outcomes("failure")).isEqualTo(1);
  }

  @Test
  void failedClosedAdmissionPageRetriesItsCursorUntilAValidEmptyPageResetsIt() {
    var closure = closure(1);
    var owner = closureOwner(closure);
    when(repository.closedAdmissionCandidates(null, 2))
        .thenReturn(List.of(closure))
        .thenReturn(List.of());
    when(repository.closedAdmissionCandidates(closure.operationUuid(), 2))
        .thenThrow(new org.jooq.exception.DataAccessException("closure page unavailable"))
        .thenReturn(List.of());
    when(ownerClient.getCanonicalGameplayRosterReference(owner.getRequest())).thenReturn(owner);
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();
    service.sweep();
    service.sweep();
    service.sweep();

    var pages = inOrder(repository);
    pages.verify(repository).closedAdmissionCandidates(null, 2);
    pages.verify(repository).closedAdmissionCandidates(closure.operationUuid(), 2);
    pages.verify(repository).closedAdmissionCandidates(closure.operationUuid(), 2);
    pages.verify(repository).closedAdmissionCandidates(null, 2);
    verify(repository, times(4)).candidates(null, 2);
    verify(repository, times(4)).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository, times(4)).purgeDisposedTombstones(2);
    verify(repository, times(4)).health();
    verify(ownerClient).getCanonicalGameplayRosterReference(owner.getRequest());
    assertThat(outcomes("failure")).isEqualTo(1);
  }

  @Test
  void waitingTransferUsesIndependentPositiveOwnerReadOutsideSqlAndNeverAcknowledgesUnknown() {
    var candidate = candidate(1, "PROTECTED");
    var waiting =
        new RetirementTransfer(
            new UUID(8, 1), "WAITING_RETIREMENT", candidate.ownerRequest(), null, null);
    when(repository.retirementTransfers(null, 2)).thenReturn(List.of(waiting));
    when(repository.retirementTransfers(waiting.observationUuid(), 2)).thenReturn(List.of(waiting));
    var nonterminal =
        CanonicalGameplayRosterReferenceEvidence.response(
            candidate.ownerRequest(), 1, new UUID(3, 1), null);
    when(ownerClient.getCanonicalGameplayRosterReference(candidate.ownerRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return nonterminal;
            })
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              throw new IllegalStateException("unavailable");
            })
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return retirement(candidate);
            });
    when(repository.observeWaitingRetirement(waiting, retirement(candidate)))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();
    service.sweep();
    verify(repository, never()).observeWaitingRetirement(any(), any());
    service.sweep();

    verify(repository).observeWaitingRetirement(waiting, retirement(candidate));
    verify(ownerClient, never()).acknowledgeCanonicalGameplayRosterRetirement(any());
    verify(ownerClient, never()).confirmCanonicalGameplayRosterRetirementAcknowledgement(any());
    verify(repository, times(3)).purgeConfirmedRetirementTransfers(2);
    assertThat(outcomes("protected")).isEqualTo(1);
    assertThat(outcomes("failure")).isEqualTo(1);
  }

  @Test
  void lostAcknowledgementRetriesStableRequestAndConfirmationUsesCommittedActualResponse() {
    var pending = transfer(1);
    var request = pending.acknowledgementRequest();
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            request, new UUID(9, 1), request.getMinimumRetentionMillis() + 1);
    var acknowledged =
        new RetirementTransfer(
            pending.observationUuid(),
            "ACKNOWLEDGED",
            pending.original(),
            request,
            acknowledgement);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(request, acknowledgement);
    var confirmationResponse =
        CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    var committed = new java.util.concurrent.atomic.AtomicBoolean();
    when(repository.retirementTransfers(null, 2)).thenReturn(List.of(pending));
    when(repository.retirementTransfers(pending.observationUuid(), 2))
        .thenReturn(List.of(pending))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              assertThat(committed.get()).isTrue();
              return List.of(acknowledged);
            })
        .thenReturn(List.of(acknowledged));
    when(ownerClient.acknowledgeCanonicalGameplayRosterRetirement(request))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              throw new IllegalStateException("ACK reply lost");
            })
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return acknowledgement;
            });
    when(repository.recordRetirementAcknowledgement(pending, acknowledgement))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              committed.set(true);
              return true;
            });
    when(ownerClient.confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              assertThat(committed.get()).isTrue();
              throw new IllegalStateException("confirmation reply lost");
            })
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return confirmationResponse;
            });
    when(repository.recordRetirementConfirmation(acknowledged, confirmationResponse))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return true;
            });
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();
    verify(repository, never()).recordRetirementAcknowledgement(any(), any());
    assertThat(outcomes("acknowledged")).isZero();
    assertThat(outcomes("confirmed")).isZero();
    service.sweep();
    verify(ownerClient, never()).confirmCanonicalGameplayRosterRetirementAcknowledgement(any());
    assertThat(outcomes("acknowledged")).isEqualTo(1);
    assertThat(outcomes("confirmed")).isZero();
    service.sweep();
    verify(repository, never()).recordRetirementConfirmation(any(), any());
    assertThat(outcomes("acknowledged")).isEqualTo(1);
    assertThat(outcomes("confirmed")).isZero();
    service.sweep();

    verify(ownerClient, times(2)).acknowledgeCanonicalGameplayRosterRetirement(request);
    verify(repository).recordRetirementAcknowledgement(pending, acknowledgement);
    verify(ownerClient, times(2))
        .confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation);
    verify(repository).recordRetirementConfirmation(acknowledged, confirmationResponse);
    verify(repository, times(4)).purgeConfirmedRetirementTransfers(2);
    assertThat(outcomes("failure")).isEqualTo(2);
    assertThat(outcomes("acknowledged")).isEqualTo(1);
    assertThat(outcomes("confirmed")).isEqualTo(1);
  }

  @Test
  void idempotentTransferNoOpsDoNotRecordAcknowledgedOrConfirmedOutcomes() {
    var pending = transfer(1);
    var acknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            pending.acknowledgementRequest(),
            new UUID(9, 1),
            pending.acknowledgementRequest().getMinimumRetentionMillis());
    var prior = transfer(2);
    var priorAcknowledgement =
        CanonicalGameplayRosterRetirementEvidence.response(
            prior.acknowledgementRequest(),
            new UUID(9, 2),
            prior.acknowledgementRequest().getMinimumRetentionMillis());
    var acknowledged =
        new RetirementTransfer(
            prior.observationUuid(),
            "ACKNOWLEDGED",
            prior.original(),
            prior.acknowledgementRequest(),
            priorAcknowledgement);
    var confirmation =
        CanonicalGameplayRosterRetirementEvidence.confirmation(
            prior.acknowledgementRequest(), priorAcknowledgement);
    var confirmationResponse =
        CanonicalGameplayRosterRetirementEvidence.confirmationResponse(confirmation);
    when(repository.retirementTransfers(null, 2)).thenReturn(List.of(pending, acknowledged));
    when(ownerClient.acknowledgeCanonicalGameplayRosterRetirement(pending.acknowledgementRequest()))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return acknowledgement;
            });
    when(repository.recordRetirementAcknowledgement(pending, acknowledgement))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return false;
            });
    when(ownerClient.confirmCanonicalGameplayRosterRetirementAcknowledgement(confirmation))
        .thenAnswer(
            ignored -> {
              assertOutsideTransaction();
              return confirmationResponse;
            });
    when(repository.recordRetirementConfirmation(acknowledged, confirmationResponse))
        .thenAnswer(
            ignored -> {
              assertSqlTransaction();
              return false;
            });
    when(repository.health()).thenReturn(new Health(0, 0));

    service.sweep();

    verify(repository).recordRetirementAcknowledgement(pending, acknowledgement);
    verify(repository).recordRetirementConfirmation(acknowledged, confirmationResponse);
    assertThat(outcomes("acknowledged")).isZero();
    assertThat(outcomes("confirmed")).isZero();
    assertThat(outcomes("failure")).isZero();
  }

  @Test
  void failedTransferPageKeepsCursorAndBothTransferStagesLeaveLaterCleanupAndHealthRunning() {
    var pending = transfer(1);
    when(repository.retirementTransfers(null, 2))
        .thenReturn(List.of(pending))
        .thenReturn(List.of());
    when(repository.retirementTransfers(pending.observationUuid(), 2))
        .thenThrow(new org.jooq.exception.DataAccessException("transfer page unavailable"))
        .thenReturn(List.of());
    when(ownerClient.acknowledgeCanonicalGameplayRosterRetirement(pending.acknowledgementRequest()))
        .thenReturn(
            CanonicalGameplayRosterRetirementEvidence.response(
                pending.acknowledgementRequest(),
                new UUID(9, 1),
                pending.acknowledgementRequest().getMinimumRetentionMillis()));
    when(repository.purgeConfirmedRetirementTransfers(2))
        .thenThrow(new org.jooq.exception.DataAccessException("confirmed cleanup unavailable"))
        .thenReturn(0);
    when(repository.health()).thenReturn(new Health(3, 1));

    service.sweep();
    service.sweep();
    service.sweep();
    service.sweep();

    var pages = inOrder(repository);
    pages.verify(repository).retirementTransfers(null, 2);
    pages.verify(repository).retirementTransfers(pending.observationUuid(), 2);
    pages.verify(repository).retirementTransfers(pending.observationUuid(), 2);
    pages.verify(repository).retirementTransfers(null, 2);
    verify(repository, times(4)).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository, times(4)).purgeDisposedTombstones(2);
    verify(repository, times(4)).purgeConfirmedRetirementTransfers(2);
    verify(repository, times(4)).health();
    assertThat(outcomes("failure")).isEqualTo(2);
    assertThat(metrics.get("firemud.canonical.roster.oldest_blocking_seconds").gauge().value())
        .isEqualTo(3);
  }

  private static RetirementTransfer transfer(long id) {
    var candidate = candidate(id, "PROTECTED");
    var request =
        net.firedevops.firemud.gamesession.v1
            .CanonicalGameplayRosterRetirementAcknowledgementRequest.newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(candidate.ownerRequest())
            .setObservedRetirement(retirement(candidate))
            .setObservationUuid(new UUID(8, id).toString())
            .setAdmissionUuid(new UUID(7, id).toString())
            .setSnapshotUuid(candidate.binding().snapshot().snapshotUuid().toString())
            .setSnapshotDigest(candidate.binding().snapshot().snapshotDigest())
            .setMinimumRetentionMillis(1000)
            .build();
    CanonicalGameplayRosterRetirementEvidence.requireAcknowledgement(request);
    return new RetirementTransfer(
        new UUID(8, id), "PENDING_ACK", candidate.ownerRequest(), request, null);
  }

  private void assertPurgeAndHealthStagesRan() {
    verify(repository).purgeOrphanOpenAdmissions(policy, 2);
    verify(repository).purgeDisposedTombstones(2);
    verify(repository).health();
    assertOutsideTransaction();
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
    var counter = metrics.find("firemud.canonical.roster.cleanup").tag("result", result).counter();
    return counter == null ? 0 : counter.count();
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

  private static ClosedAdmissionCandidate closure(long id) {
    var original = candidate(id, "PROTECTED").ownerRequest();
    var request =
        net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest
            .newBuilder()
            .setSchemaVersion(1)
            .setOriginalSelection(original)
            .setRecoveryUuid(new UUID(4, id).toString())
            .build();
    UUID admission = new UUID(5, id);
    var response =
        net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence.response(
            request, admission, 1, new UUID(3, 1), null);
    return new ClosedAdmissionCandidate(
        UUID.fromString(original.getOperationUuid()), admission, response);
  }

  private static GetCanonicalGameplayRosterReferenceResponse closureOwner(
      ClosedAdmissionCandidate closure) {
    var request = closure.response().getRequest();
    return CanonicalGameplayRosterReferenceEvidence.response(
            request.getOriginalSelection(), 1, new UUID(3, 1), null)
        .toBuilder()
        .setRecoveryUuid(request.getRecoveryUuid())
        .build();
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
