package net.firedevops.firemud.recovery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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
}
