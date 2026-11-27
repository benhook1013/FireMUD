package net.firedevops.firemud.recovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReason;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReasonCode;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.LaunchAssociation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.MaintenanceLockPresence;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Mutation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Target;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal.RetrySource;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseCommands.Resume;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.InitialGameplayReleaseEvidence;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PeerStorageBinding;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Phase;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PreparePlayableStateFencedEffect;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionResult;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PublicTransitionTuple;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.TransitionVerb;
import net.firedevops.firemud.recovery.model.MaintenanceLockCredential;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEffectPort.EffectApplyResult;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider.EvidenceResolution;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider.ResolutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class InitialGameplayReleaseControllerTest {
  private static final String DIGEST = "c".repeat(64);

  @Test
  void reconcileRefusesCallerTransactionBeforeClaimOrOwnerInteraction() throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    Connection connection = mock(Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    var caller = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    var journal = mock(InitialGameplayReleaseJournal.class);
    var evidence = mock(InitialGameplayReleaseEvidenceProvider.class);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    var controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            evidence,
            port);

    caller.executeWithoutResult(status -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      assertThatThrownBy(() -> controller.reconcileNextEffect(UUID.randomUUID()))
          .isInstanceOf(OwnerBoundaryUnavailableException.class)
          .hasMessage("INITIAL_GAMEPLAY_RELEASE_RECONCILIATION_REQUIRES_NO_CALLER_TRANSACTION");
      status.setRollbackOnly();
    });
    verify(connection).rollback();
    verifyNoInteractions(journal, evidence, port);
  }

  @Test
  void ownerResultsRejectMissingStatus() {
    assertThatThrownBy(
            () -> new InitialGameplayReleaseEvidenceProvider.EvidenceResolution(null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InitialGameplayReleaseEffectPort.EffectApplyResult(null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new InitialGameplayReleaseEffectPort.EffectReadbackResolution(null, null, null))
        .isInstanceOf(NullPointerException.class);
    assertThat(InitialGameplayReleaseEvidenceProvider.EvidenceResolution.unavailable().status())
        .isEqualTo(ResolutionStatus.UNAVAILABLE);
    assertThat(InitialGameplayReleaseEffectPort.EffectApplyResult.applied().status())
        .isEqualTo(InitialGameplayReleaseEffectPort.ApplyStatus.APPLIED);
  }

  @Test
  void validTypedRecoverDeniesBeforeAnyJournalEvidenceOrEffectInteraction() {
    InitialGameplayReleaseJournal journal = mock(InitialGameplayReleaseJournal.class);
    InitialGameplayReleaseEvidenceProvider evidenceProvider =
        mock(InitialGameplayReleaseEvidenceProvider.class);
    InitialGameplayReleaseEffectPort effectPort = mock(InitialGameplayReleaseEffectPort.class);
    InitialGameplayReleaseController controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            evidenceProvider,
            effectPort);
    // Valid selectors are deliberately insufficient to prove authority or acquire a lock.
    InitialGameplayRecoverRequest request =
        new InitialGameplayRecoverRequest(
            UUID.randomUUID(),
            new InitialGameplayRecoverRequest.Scope(
                UUID.randomUUID(), "release-target", UUID.randomUUID()),
            new Target(
                new LaunchAssociation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "world",
                    UUID.randomUUID(),
                    "launch-1",
                    "sha256:" + DIGEST,
                    "sha256:" + DIGEST,
                    "sha256:" + DIGEST),
                new InitialGameplayRecoverRequest.PeerStorageBinding(
                    "release-target",
                    "cluster-1",
                    "spiffe://firemud/ns/release-target/sa/game-session-service",
                    "storage-1",
                    "revision-1",
                    "sha256:" + DIGEST),
                "initial-release-evidence-1",
                "sha256:" + DIGEST),
            new Mutation(UUID.randomUUID(), MaintenanceLockPresence.RESULT_ONLY),
            new AuditReason(AuditReasonCode.INITIAL_GAMEPLAY_RELEASE, "Initial release request"));

    assertThatThrownBy(() -> controller.recover(request))
        .isInstanceOf(OwnerBoundaryUnavailableException.class)
        .hasMessage("INITIAL_GAMEPLAY_RECOVER_AUTHORIZATION_AND_EXCLUSIVE_FENCE_UNAVAILABLE");
    verifyNoInteractions(journal, evidenceProvider, effectPort);
  }

  @Test
  void unconfiguredEvidenceProviderDeniesReadinessInsteadOfFabricatingProof() {
    DeniedInitialGameplayReleaseEvidenceProvider provider =
        new DeniedInitialGameplayReleaseEvidenceProvider();
    Scope scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "initial-release-evidence-1");

    assertThat(provider.read(scope).status()).isEqualTo(ResolutionStatus.UNAVAILABLE);
    assertThat(provider.read(scope).evidence()).isNull();
    var journal = mock(InitialGameplayReleaseJournal.class);
    var operation =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation.class);
    var lock =
        net.firedevops.firemud.recovery.model.MaintenanceLockCredential.issue(UUID.randomUUID());
    when(operation.scope()).thenReturn(scope);
    when(operation.maintenanceLockIdentity()).thenReturn(lock.identity());
    when(journal.find(scope.operationId())).thenReturn(java.util.Optional.of(operation));
    var controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            provider,
            new DeniedInitialGameplayReleaseEffectPort());
    assertThatThrownBy(() -> controller.refreshReadiness(scope.operationId(), lock))
        .isInstanceOf(OwnerBoundaryUnavailableException.class)
        .hasMessage("INITIAL_GAMEPLAY_RELEASE_EVIDENCE_UNAVAILABLE");
    verify(journal, never()).bindVerifiedEvidence(any(), any(), any(), any(), any());
  }

  @Test
  void unconfiguredGameSessionEffectPortDoesNotDispatch() {
    DeniedInitialGameplayReleaseEffectPort port = new DeniedInitialGameplayReleaseEffectPort();
    Scope scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "initial-release-evidence-1");
    PreparePlayableStateFencedEffect effect =
        new PreparePlayableStateFencedEffect(
            scope.operationId(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            scope,
            DIGEST,
            "proof-identity-1");

    var result = port.apply(effect, UUID.randomUUID(), 1);
    assertThat(result.status())
        .isEqualTo(InitialGameplayReleaseEffectPort.ApplyStatus.NOT_DISPATCHED);
    assertThat(result.outcomeCode()).isEqualTo("GAME_SESSION_EFFECT_OWNER_UNAVAILABLE");
    assertThat(port.readback(effect, UUID.randomUUID(), 1).status())
        .isEqualTo(InitialGameplayReleaseEffectPort.ReadbackStatus.UNAVAILABLE);
  }

  @Test
  void appliedEffectReadsAndRecordsTheExactClaimBeforeReturning() {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    var codec = mock(InitialGameplayReleaseTupleCodec.class);
    var operation = mock(Operation.class);
    var after = mock(Operation.class);
    var scope = mock(Scope.class);
    var effect = mock(PreparePlayableStateFencedEffect.class);
    var pending = mock(OutboxEntry.class);
    var dispatched = mock(OutboxEntry.class);
    var operationId = UUID.randomUUID();
    var effectId = UUID.randomUUID();
    var now = java.time.Instant.parse("2026-11-18T12:00:00Z");
    var readback =
        mock(
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback.class);
    when(readback.observedAt()).thenReturn(now);
    when(operation.scope()).thenReturn(scope);
    when(scope.operationId()).thenReturn(operationId);
    when(pending.state()).thenReturn(EffectOutboxState.PENDING);
    when(pending.effect()).thenReturn(effect);
    when(effect.effectId()).thenReturn(effectId);
    when(dispatched.state()).thenReturn(EffectOutboxState.DISPATCHED);
    when(dispatched.effect()).thenReturn(effect);
    when(dispatched.attemptCount()).thenReturn(1);
    when(journal.findNextEffect(operationId)).thenReturn(java.util.Optional.of(pending));
    when(journal.find(operationId)).thenReturn(java.util.Optional.of(operation));
    var attempt = new AtomicReference<UUID>();
    when(journal.claimEffect(
            org.mockito.ArgumentMatchers.eq(operationId),
            org.mockito.ArgumentMatchers.eq(effectId),
            any(),
            org.mockito.ArgumentMatchers.eq(now)))
        .thenAnswer(invocation -> {
          UUID claim = invocation.getArgument(2);
          attempt.set(claim);
          when(dispatched.lastAttemptId()).thenReturn(claim);
          return dispatched;
        });
    when(port.apply(
            org.mockito.ArgumentMatchers.eq(effect), any(), org.mockito.ArgumentMatchers.eq(1)))
        .thenReturn(EffectApplyResult.applied());
    var available = InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(readback);
    when(port.readback(
            org.mockito.ArgumentMatchers.eq(effect), any(), org.mockito.ArgumentMatchers.eq(1)))
        .thenReturn(available);
    when(journal.recordEffectReadback(dispatched, readback, now)).thenReturn(after);
    var controller =
        new InitialGameplayReleaseController(
            journal,
            codec,
            mock(InitialGameplayReleaseEvidenceProvider.class),
            port,
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));

    assertThat(controller.reconcileNextEffect(operationId)).isSameAs(after);
    assertThat(attempt.get()).isNotNull();
    var order = org.mockito.Mockito.inOrder(journal, port);
    order.verify(journal).claimEffect(operationId, effectId, attempt.get(), now);
    order.verify(port).apply(effect, attempt.get(), 1);
    order.verify(port).readback(effect, attempt.get(), 1);
    order.verify(journal).recordEffectReadback(dispatched, readback, now);
    org.mockito.Mockito.verifyNoMoreInteractions(port);
  }

  @Test
  void pendingEffectClaimedByAnotherAttemptDoesNotDispatch() {
    assertPendingClaimHandling(false, null, null);
  }

  @Test
  void pendingEffectApplyExceptionRecordsAmbiguousNotRetryable() {
    assertPendingClaimHandling(true, null, null);
  }

  @Test
  void pendingEffectAmbiguousResultUsesClaimedDispatch() {
    assertPendingClaimHandling(true, EffectApplyResult.ambiguous("OWNER_PENDING"), null);
  }

  @Test
  void pendingEffectNotDispatchedRecordsLocalRetryProvenance() {
    assertPendingClaimHandling(
        true, EffectApplyResult.notDispatched("TRANSPORT_REFUSED"), RetrySource.NOT_DISPATCHED);
  }

  @Test
  void pendingEffectDefinitiveOwnerRejectionRecordsOwnerRetryProvenance() {
    assertPendingClaimHandling(
        true, EffectApplyResult.retryableNoEffect("OWNER_REJECTED"), RetrySource.OWNER_REJECTED);
  }

  private void assertPendingClaimHandling(
      boolean ownsClaim, EffectApplyResult applyResult, RetrySource expectedRetrySource) {
    assertClaimHandling(EffectOutboxState.PENDING, ownsClaim, applyResult, expectedRetrySource);
  }

  @Test
  void retryableEffectAppliesWithTheNewAttemptAndNextOrdinal() {
    assertClaimHandling(
        EffectOutboxState.RETRYABLE, true, EffectApplyResult.ambiguous("OWNER_PENDING"), null);
  }

  private void assertClaimHandling(
      EffectOutboxState priorState,
      boolean ownsClaim,
      EffectApplyResult applyResult,
      RetrySource expectedRetrySource) {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    var operation = mock(Operation.class);
    var after = mock(Operation.class);
    var scope = mock(Scope.class);
    var operationId = UUID.randomUUID();
    var effect = mock(PreparePlayableStateFencedEffect.class);
    var effectId = UUID.randomUUID();
    when(scope.operationId()).thenReturn(operationId);
    when(operation.scope()).thenReturn(scope);
    when(effect.effectId()).thenReturn(effectId);
    var selected = mock(OutboxEntry.class);
    when(selected.effect()).thenReturn(effect);
    when(selected.state()).thenReturn(priorState);
    boolean pending = priorState == EffectOutboxState.PENDING;
    int claimedOrdinal = pending ? 1 : 3;
    UUID previousAttempt = pending ? null : UUID.randomUUID();
    when(selected.attemptCount()).thenReturn(pending ? 0 : 1);
    when(selected.lastAttemptId()).thenReturn(previousAttempt);
    when(journal.find(operationId))
        .thenReturn(java.util.Optional.of(operation), java.util.Optional.of(after));
    when(journal.findNextEffect(operationId)).thenReturn(java.util.Optional.of(selected));
    var claimedAttempt = new AtomicReference<UUID>();
    var claimed =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry.class);
    when(journal.claimEffect(
            org.mockito.ArgumentMatchers.eq(operationId),
            org.mockito.ArgumentMatchers.eq(effectId),
            any(),
            any()))
        .thenAnswer(invocation -> {
          UUID requested = invocation.getArgument(2);
          claimedAttempt.set(requested);
          when(claimed.state())
              .thenReturn(
                  net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel
                      .EffectOutboxState.DISPATCHED);
          when(claimed.lastAttemptId())
              .thenReturn(
                  ownsClaim
                      ? requested
                      : new UUID(
                          ~requested.getMostSignificantBits(),
                          requested.getLeastSignificantBits()));
          if (ownsClaim) {
            when(claimed.effect()).thenReturn(effect);
            when(claimed.attemptCount()).thenReturn(claimedOrdinal);
          }
          return claimed;
        });
    if (ownsClaim && applyResult == null) {
      when(port.apply(org.mockito.ArgumentMatchers.eq(effect), any(), anyInt()))
          .thenThrow(new IllegalStateException("response lost after possible owner send"));
    } else if (ownsClaim) {
      when(port.apply(org.mockito.ArgumentMatchers.eq(effect), any(), anyInt()))
          .thenReturn(applyResult);
    }
    var controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            mock(InitialGameplayReleaseEvidenceProvider.class),
            port);
    assertThat(controller.reconcileNextEffect(operationId)).isSameAs(after);
    assertThat(claimedAttempt.get()).isNotNull();
    assertThat(claimedAttempt.get()).isNotEqualTo(previousAttempt);
    if (expectedRetrySource == null) {
      verify(journal, never()).recordRetryableEffectForDispatch(any(), any(), any(), any());
    } else {
      verify(journal)
          .recordRetryableEffectForDispatch(
              org.mockito.ArgumentMatchers.same(claimed),
              org.mockito.ArgumentMatchers.eq(expectedRetrySource),
              org.mockito.ArgumentMatchers.eq(applyResult.outcomeCode()),
              any());
    }
    verify(journal, never()).recordEffectReadback(any(), any(), any());
    if (!ownsClaim) {
      verifyNoInteractions(port);
    } else {
      verify(port, org.mockito.Mockito.only()).apply(effect, claimedAttempt.get(), claimedOrdinal);
    }
    if (ownsClaim && expectedRetrySource == null) {
      verify(journal)
          .recordAmbiguousEffectForDispatch(
              org.mockito.ArgumentMatchers.same(claimed),
              org.mockito.ArgumentMatchers.eq(
                  applyResult == null ? "EFFECT_CALL_OUTCOME_UNKNOWN" : applyResult.outcomeCode()),
              any());
    } else {
      verify(journal, never()).recordAmbiguousEffectForDispatch(any(), any(), any());
    }
  }

  @Test
  void applyOutcomeCodesUseJournalContract() {
    for (String invalid :
        java.util.List.of("owner_busy", "owner-unavailable", "X".repeat(65), "")) {
      assertThatThrownBy(
              () -> InitialGameplayReleaseEffectPort.EffectApplyResult.retryableNoEffect(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> InitialGameplayReleaseEffectPort.EffectApplyResult.notDispatched(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(
              () -> InitialGameplayReleaseEffectPort.EffectApplyResult.ambiguous(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(
            InitialGameplayReleaseEffectPort.EffectApplyResult.retryableNoEffect("OWNER_BUSY")
                .outcomeCode())
        .isEqualTo("OWNER_BUSY");
  }

  @ParameterizedTest
  @EnumSource(ResolutionStatus.class)
  void ownerEvidenceFailureReturnsConcurrentExactCommittedTransition(ResolutionStatus status) {
    assertEvidenceFailureTransition(status, true);
  }

  @ParameterizedTest
  @EnumSource(ResolutionStatus.class)
  void ownerEvidenceFailureWithoutCommittedTransitionStillFailsClosed(ResolutionStatus status) {
    assertEvidenceFailureTransition(status, false);
  }

  @Test
  void missingMigrationDispositionStillReturnsTheConcurrentRetainedResult() {
    assertEvidenceFailureTransition(null, true);
  }

  private void assertEvidenceFailureTransition(ResolutionStatus status, boolean committed) {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var codec = mock(InitialGameplayReleaseTupleCodec.class);
    var provider = mock(InitialGameplayReleaseEvidenceProvider.class);
    var scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "evidence");
    var before = mock(Operation.class);
    var after = new PublicTransitionResult(scope.operationId(), Phase.RESUME_AUTHORIZED);
    var evidence = mock(InitialGameplayReleaseEvidence.class);
    var lock = MaintenanceLockCredential.issue(UUID.randomUUID());
    when(before.scope()).thenReturn(scope);
    when(before.maintenanceLockIdentity()).thenReturn(lock.identity());
    when(before.evidence()).thenReturn(evidence);
    when(evidence.evidenceIdentity()).thenReturn("proof");
    when(codec.digest(scope)).thenReturn(DIGEST);
    when(codec.digest(evidence)).thenReturn(DIGEST);
    when(journal.find(scope.operationId())).thenReturn(java.util.Optional.of(before));
    var tuple =
        new PublicTransitionTuple(
            scope.operationId(),
            DIGEST,
            TransitionVerb.RESUME,
            Phase.AWAITING_RESUME,
            lock.identity(),
            scope.evidenceRef(),
            "proof",
            DIGEST);
    var retainedTransition =
        new AtomicReference<>(java.util.Optional.<PublicTransitionResult>empty());
    when(journal.findTransition(tuple)).thenAnswer(invocation -> retainedTransition.get());
    InitialGameplayReleaseEvidence changedEvidence = null;
    if (status == ResolutionStatus.AVAILABLE) {
      changedEvidence = mock(InitialGameplayReleaseEvidence.class);
      when(changedEvidence.evidenceIdentity()).thenReturn("proof");
      when(codec.digest(changedEvidence)).thenReturn("d".repeat(64));
    }
    var resolution = status == null ? null : new EvidenceResolution(status, changedEvidence);
    when(provider.read(scope))
        .thenAnswer(invocation -> {
          if (committed) {
            retainedTransition.set(java.util.Optional.of(after));
          }
          if (status == null) {
            new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.MigrationProof(
                UUID.randomUUID(), null, "storage", "revision", DIGEST);
          }
          return resolution;
        });
    var controller =
        new InitialGameplayReleaseController(
            journal, codec, provider, mock(InitialGameplayReleaseEffectPort.class));
    var command = new Resume(scope.operationId(), Phase.AWAITING_RESUME, lock, scope.evidenceRef());

    if (committed) {
      assertThat(controller.resume(command)).isSameAs(after);
    } else if (status == ResolutionStatus.AVAILABLE) {
      assertThatThrownBy(() -> controller.resume(command))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("owner evidence changed after readiness was recorded");
    } else {
      assertThatThrownBy(() -> controller.resume(command))
          .isInstanceOf(OwnerBoundaryUnavailableException.class)
          .hasMessage("INITIAL_GAMEPLAY_RELEASE_EVIDENCE_" + status.name());
    }
    verify(journal, org.mockito.Mockito.times(2)).findTransition(tuple);
    verify(journal, never()).transition(any(), any());
  }

  @Test
  void emptyOutboxReloadsLatestOperation() {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var operationId = UUID.randomUUID();
    var before =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation.class);
    var after =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation.class);
    var snapshot = new AtomicReference<Operation>(before);
    when(journal.find(operationId)).thenAnswer(invocation -> java.util.Optional.of(snapshot.get()));
    when(journal.findNextEffect(operationId))
        .thenAnswer(invocation -> {
          snapshot.set(after);
          return java.util.Optional.empty();
        });
    var controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            mock(InitialGameplayReleaseEvidenceProvider.class),
            mock(InitialGameplayReleaseEffectPort.class));
    assertThat(controller.reconcileNextEffect(operationId)).isSameAs(after);
  }

  @Test
  void unavailableReadbackReloadsLatestOperation() {
    assertReadbackFailureReloadsLatestOperation(ReadbackFailure.UNAVAILABLE);
  }

  @Test
  void failedReadbackReloadsLatestOperation() {
    assertReadbackFailureReloadsLatestOperation(ReadbackFailure.THROWN);
  }

  @Test
  void missingReadbackReloadsLatestOperation() {
    assertReadbackFailureReloadsLatestOperation(ReadbackFailure.MISSING);
  }

  private enum ReadbackFailure {
    UNAVAILABLE,
    THROWN,
    MISSING
  }

  private void assertReadbackFailureReloadsLatestOperation(ReadbackFailure failure) {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var effectPort = mock(InitialGameplayReleaseEffectPort.class);
    var scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "evidence");
    var before = operationForReadback(scope);
    var after =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation.class);
    var codec =
        new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build());
    var effect =
        new PreparePlayableStateFencedEffect(
            scope.operationId(),
            UUID.randomUUID(),
            before.maintenanceLockIdentity(),
            scope,
            codec.operationTupleDigest(before),
            before.evidence().evidenceIdentity());
    var attempt = UUID.randomUUID();
    var outbox =
        new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry(
            UUID.randomUUID(),
            effect,
            DIGEST,
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState
                .DISPATCHED,
            1,
            attempt,
            null,
            null);
    var snapshot = new AtomicReference<>(before);
    when(journal.find(scope.operationId()))
        .thenAnswer(invocation -> java.util.Optional.of(snapshot.get()));
    when(journal.findNextEffect(scope.operationId())).thenReturn(java.util.Optional.of(outbox));
    switch (failure) {
      case THROWN ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount()))
              .thenAnswer(invocation -> {
                snapshot.set(after);
                throw new IllegalStateException("owner readback failed");
              });
      case UNAVAILABLE ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount()))
              .thenAnswer(invocation -> {
                snapshot.set(after);
                return InitialGameplayReleaseEffectPort.EffectReadbackResolution.unavailable();
              });
      case MISSING ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount()))
              .thenAnswer(invocation -> {
                snapshot.set(after);
                return null;
              });
    }
    var controller =
        new InitialGameplayReleaseController(
            journal, codec, mock(InitialGameplayReleaseEvidenceProvider.class), effectPort);

    assertThat(controller.reconcileNextEffect(scope.operationId())).isSameAs(after);
    verify(effectPort).readback(effect, attempt, outbox.attemptCount());
    verify(journal, never()).recordEffectReadback(any(), any(), any());
  }

  @Test
  void retainedEffectForDifferentPlayableNamespaceDoesNotReachOwnerReadback() {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var port = mock(InitialGameplayReleaseEffectPort.class);
    var scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "evidence");
    var operation = operationForReadback(scope);
    var otherNamespace =
        new Scope(
            scope.operationId(),
            scope.canonicalTenantId(),
            scope.targetNamespace(),
            UUID.randomUUID(),
            scope.peerStorageBinding(),
            scope.evidenceRef());
    var codec =
        new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build());
    var effect =
        new PreparePlayableStateFencedEffect(
            scope.operationId(),
            UUID.randomUUID(),
            operation.maintenanceLockIdentity(),
            otherNamespace,
            codec.operationTupleDigest(operation),
            operation.evidence().evidenceIdentity());
    var outbox =
        new OutboxEntry(
            UUID.randomUUID(),
            effect,
            codec.digest(effect),
            EffectOutboxState.DISPATCHED,
            1,
            UUID.randomUUID(),
            null,
            null);
    when(journal.find(scope.operationId())).thenReturn(java.util.Optional.of(operation));
    when(journal.findNextEffect(scope.operationId())).thenReturn(java.util.Optional.of(outbox));
    var controller =
        new InitialGameplayReleaseController(
            journal, codec, mock(InitialGameplayReleaseEvidenceProvider.class), port);

    assertThatThrownBy(() -> controller.reconcileNextEffect(scope.operationId()))
        .isInstanceOf(InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("operation-owned tuple");
    verifyNoInteractions(port);
  }

  private static Operation operationForReadback(Scope scope) {
    var operation = mock(Operation.class);
    var evidence = mock(InitialGameplayReleaseEvidence.class);
    when(evidence.evidenceIdentity()).thenReturn("proof");
    when(operation.scope()).thenReturn(scope);
    when(operation.maintenanceLockIdentity()).thenReturn(UUID.randomUUID());
    when(operation.evidence()).thenReturn(evidence);
    return operation;
  }

  @Test
  void availableReadbackRequiresReceiptObservationTime() {
    var readback =
        mock(
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback.class);
    var observedAt = java.time.Instant.parse("2026-11-18T12:00:00Z");
    when(readback.observedAt()).thenReturn(observedAt);
    var status = InitialGameplayReleaseEffectPort.ReadbackStatus.AVAILABLE;
    assertThat(
            new InitialGameplayReleaseEffectPort.EffectReadbackResolution(
                status, readback, observedAt))
        .isEqualTo(InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(readback));
    assertThatThrownBy(() ->
            new InitialGameplayReleaseEffectPort.EffectReadbackResolution(
                status, readback, observedAt.plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must match receipt");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void currentOperationSnapshotAcceptsExactOwnerReadbackOnlyWhenNotFutureDated(boolean future) {
    var journal = mock(InitialGameplayReleaseJournal.class);
    var effectPort = mock(InitialGameplayReleaseEffectPort.class);
    var scope =
        new Scope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "release-target",
            UUID.randomUUID(),
            new PeerStorageBinding(
                "release-target", "cluster-1", "recovery-peer", "storage-1", "revision-1", DIGEST),
            "evidence");
    var operation = operationForReadback(scope);
    var codec =
        new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build());
    var effect =
        new PreparePlayableStateFencedEffect(
            scope.operationId(),
            UUID.randomUUID(),
            operation.maintenanceLockIdentity(),
            scope,
            codec.operationTupleDigest(operation),
            operation.evidence().evidenceIdentity());
    var attempt = UUID.randomUUID();
    var outbox =
        new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry(
            UUID.randomUUID(),
            effect,
            codec.digest(effect),
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState
                .DISPATCHED,
            1,
            attempt,
            null,
            null);
    var t0 = java.time.Instant.parse("2026-11-18T12:00:00Z");
    var t1 = t0.plusSeconds(1);
    var t2 = t0.plusSeconds(2);
    var t3 = t0.plusSeconds(3);
    var clock = new MutableClock(t0);
    var readback =
        new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback(
            scope.operationId(),
            effect.effectId(),
            attempt,
            outbox.attemptCount(),
            effect.kind(),
            effect.operationTupleDigest(),
            outbox.payloadDigest(),
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState
                .PREPARED_FENCED,
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel
                .EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "readback",
            future ? t3 : t1);
    var before = mock(Operation.class);
    when(before.scope()).thenReturn(scope);
    var snapshot = new AtomicReference<>(before);
    when(journal.find(scope.operationId()))
        .thenAnswer(invocation -> java.util.Optional.of(snapshot.get()));
    when(journal.findNextEffect(scope.operationId()))
        .thenAnswer(invocation -> {
          snapshot.set(operation);
          return java.util.Optional.of(outbox);
        });
    when(effectPort.readback(effect, attempt, outbox.attemptCount()))
        .thenAnswer(invocation -> {
          clock.setInstant(t2);
          return InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(readback);
        });
    var controller =
        new InitialGameplayReleaseController(
            journal, codec, mock(InitialGameplayReleaseEvidenceProvider.class), effectPort, clock);
    if (future) {
      assertThatThrownBy(() -> controller.reconcileNextEffect(scope.operationId()))
          .isInstanceOf(InitialGameplayReleaseConflictException.class)
          .hasMessageContaining("observation time changed");
      verify(journal, never()).recordEffectReadback(any(), any(), any());
    } else {
      when(journal.recordEffectReadback(outbox, readback, t2)).thenReturn(operation);
      assertThat(controller.reconcileNextEffect(scope.operationId())).isSameAs(operation);
      verify(journal).recordEffectReadback(outbox, readback, t2);
    }
  }

  private static final class MutableClock extends java.time.Clock {
    private final AtomicReference<java.time.Instant> instant;

    private MutableClock(java.time.Instant instant) {
      this.instant = new AtomicReference<>(instant);
    }

    private void setInstant(java.time.Instant value) {
      instant.set(value);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      if (!java.time.ZoneOffset.UTC.equals(zone)) {
        throw new UnsupportedOperationException("UTC only");
      }
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return instant.get();
    }
  }
}
