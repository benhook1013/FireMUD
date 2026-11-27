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
    var pending =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.OutboxEntry.class);
    when(pending.effect()).thenReturn(effect);
    when(pending.state())
        .thenReturn(
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState
                .PENDING);
    when(journal.find(operationId))
        .thenReturn(java.util.Optional.of(operation), java.util.Optional.of(after));
    when(journal.findNextEffect(operationId)).thenReturn(java.util.Optional.of(pending));
    var claimedAttempt = new java.util.concurrent.atomic.AtomicReference<UUID>();
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
            when(claimed.attemptCount()).thenReturn(2);
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
      verify(port).apply(effect, claimedAttempt.get(), 2);
      verify(port, never()).readback(any(), any(), anyInt());
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
    var after = mock(Operation.class);
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
    when(journal.findTransition(tuple))
        .thenReturn(
            java.util.Optional.empty(),
            committed ? java.util.Optional.of(after) : java.util.Optional.empty());
    InitialGameplayReleaseEvidence changedEvidence = null;
    if (status == ResolutionStatus.AVAILABLE) {
      changedEvidence = mock(InitialGameplayReleaseEvidence.class);
      when(changedEvidence.evidenceIdentity()).thenReturn("proof");
      when(codec.digest(changedEvidence)).thenReturn("d".repeat(64));
    }
    if (status == null) {
      when(provider.read(scope))
          .thenAnswer(invocation ->
              new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.MigrationProof(
                  UUID.randomUUID(), null, "storage", "revision", DIGEST));
    } else {
      when(provider.read(scope)).thenReturn(new EvidenceResolution(status, changedEvidence));
    }
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
    when(journal.find(operationId))
        .thenReturn(java.util.Optional.of(before), java.util.Optional.of(after));
    when(journal.findNextEffect(operationId)).thenReturn(java.util.Optional.empty());
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
    when(journal.find(scope.operationId()))
        .thenReturn(java.util.Optional.of(before), java.util.Optional.of(after));
    when(journal.findNextEffect(scope.operationId())).thenReturn(java.util.Optional.of(outbox));
    switch (failure) {
      case THROWN ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount()))
              .thenThrow(new IllegalStateException("owner readback failed"));
      case UNAVAILABLE ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount()))
              .thenReturn(InitialGameplayReleaseEffectPort.EffectReadbackResolution.unavailable());
      case MISSING ->
          when(effectPort.readback(effect, attempt, outbox.attemptCount())).thenReturn(null);
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
            codec.operationTupleDigest(
                otherNamespace, operation.maintenanceLockIdentity(), operation.evidence()),
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

  @Test
  void futureDatedOwnerReadbackCannotAdvanceJournal() {
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
            DIGEST,
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectOutboxState
                .DISPATCHED,
            1,
            attempt,
            null,
            null);
    var now = java.time.Instant.parse("2026-11-18T12:00:00Z");
    var readback =
        new net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadback(
            scope.operationId(),
            effect.effectId(),
            attempt,
            outbox.attemptCount(),
            effect.kind(),
            DIGEST,
            DIGEST,
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.EffectReadbackState
                .PREPARED_FENCED,
            net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel
                .EffectReadbackAuthority.GAME_SESSION_DURABLE_CURRENT_STATE,
            "readback",
            now.plusSeconds(1));
    when(journal.find(scope.operationId())).thenReturn(java.util.Optional.of(operation));
    when(journal.findNextEffect(scope.operationId())).thenReturn(java.util.Optional.of(outbox));
    when(effectPort.readback(effect, attempt, outbox.attemptCount()))
        .thenReturn(InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(readback));
    var controller =
        new InitialGameplayReleaseController(
            journal,
            codec,
            mock(InitialGameplayReleaseEvidenceProvider.class),
            effectPort,
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    assertThatThrownBy(() -> controller.reconcileNextEffect(scope.operationId()))
        .isInstanceOf(
            net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException.class)
        .hasMessageContaining("observation time changed");
    verify(journal, never()).recordEffectReadback(any(), any(), any());
  }
}
