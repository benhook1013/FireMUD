package net.firedevops.firemud.recovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReason;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.AuditReasonCode;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.LaunchAssociation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.MaintenanceLockPresence;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Mutation;
import net.firedevops.firemud.common.recovery.InitialGameplayRecoverRequest.Target;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseJournal;
import net.firedevops.firemud.recovery.journal.InitialGameplayReleaseTupleCodec;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PeerStorageBinding;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.PreparePlayableStateFencedEffect;
import net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Scope;
import net.firedevops.firemud.recovery.service.InitialGameplayReleaseEvidenceProvider.ResolutionStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class InitialGameplayReleaseControllerTest {
  private static final String DIGEST = "c".repeat(64);

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

    assertThat(port.apply(effect, UUID.randomUUID()).status())
        .isEqualTo(InitialGameplayReleaseEffectPort.ApplyStatus.RETRYABLE_NO_EFFECT);
    assertThat(port.readback(effect, UUID.randomUUID()).status())
        .isEqualTo(InitialGameplayReleaseEffectPort.ReadbackStatus.UNAVAILABLE);
  }

  @Test
  void applyOutcomeCodesUseJournalContract() {
    for (String invalid :
        java.util.List.of("owner_busy", "owner-unavailable", "X".repeat(65), "")) {
      assertThatThrownBy(
              () -> InitialGameplayReleaseEffectPort.EffectApplyResult.retryableNoEffect(invalid))
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
    var operation =
        mock(net.firedevops.firemud.recovery.model.InitialGameplayReleaseModel.Operation.class);
    when(operation.scope()).thenReturn(scope);
    var effect =
        new PreparePlayableStateFencedEffect(
            scope.operationId(), UUID.randomUUID(), UUID.randomUUID(), scope, DIGEST, "proof");
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
    when(effectPort.readback(effect, attempt))
        .thenReturn(InitialGameplayReleaseEffectPort.EffectReadbackResolution.available(readback));
    var controller =
        new InitialGameplayReleaseController(
            journal,
            new InitialGameplayReleaseTupleCodec(JsonMapper.builder().findAndAddModules().build()),
            mock(InitialGameplayReleaseEvidenceProvider.class),
            effectPort,
            java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
    assertThatThrownBy(() -> controller.reconcileNextEffect(scope.operationId()))
        .isInstanceOf(
            net.firedevops.firemud.recovery.journal.InitialGameplayReleaseConflictException.class);
    verify(journal, never()).recordEffectReadback(any(), any(), any(), any(), any());
  }
}
