package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Component-only assembly boundary proofs; mocked owner/client results are not producer proof. */
class WorldCanonicalInstancePreparationAssemblyServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = UUID.randomUUID();
  private static final UUID INSTANCE_ID = UUID.randomUUID();
  private static final UUID BINDING_TENANT_ID = UUID.randomUUID();
  private static final UUID BINDING_SOURCE_OPERATION_ID = UUID.randomUUID();
  private static final UUID BINDING_VERSION_ID = UUID.randomUUID();
  private static final String BINDING_COMMIT_ID = UUID.randomUUID().toString();
  private static final String CONTROL_REQUEST_ID = "control-request";

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void selectorIsMinimalAndReadsUseOnlyTheConfiguredNamespace() {
    var launchBindings = mock(WorldCompleteLaunchBindingRepository.class);
    var sourceIntakes = mock(WorldAuthoredSourceIntakeRepository.class);
    var versionIdentities = mock(WorldAuthoredVersionIdentityRepository.class);
    var frozenTopologies = mock(WorldCanonicalFrozenTopologyRepository.class);
    var gameSession = mock(CanonicalGameInstanceLaunchAssociationClient.class);
    var gameDesign = mock(AuthoredWorldLaunchDescriptorClient.class);
    when(launchBindings.read(NAMESPACE, TENANT_ID, CONTROL_REQUEST_ID))
        .thenReturn(Optional.empty());
    var service =
        new WorldCanonicalInstancePreparationAssemblyService(
            NAMESPACE,
            launchBindings,
            sourceIntakes,
            versionIdentities,
            frozenTopologies,
            gameSession,
            gameDesign);
    var selector =
        new WorldCanonicalInstancePreparationAssemblyService.Selector(
            TENANT_ID, INSTANCE_ID, CONTROL_REQUEST_ID);

    assertThatThrownBy(() -> service.assemble(selector))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("No committed World complete launch binding");

    verify(launchBindings).read(NAMESPACE, TENANT_ID, CONTROL_REQUEST_ID);
    verifyNoInteractions(
        sourceIntakes, versionIdentities, frozenTopologies, gameSession, gameDesign);
    assertThatThrownBy(
            () ->
                new WorldCanonicalInstancePreparationAssemblyService(
                    "not a namespace",
                    launchBindings,
                    sourceIntakes,
                    versionIdentities,
                    frozenTopologies,
                    gameSession,
                    gameDesign))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical DNS label");
  }

  @Test
  void rejectsAmbientTransactionAndSynchronizationBeforeAnyOwnerOrRemoteRead() {
    var launchBindings = mock(WorldCompleteLaunchBindingRepository.class);
    var sourceIntakes = mock(WorldAuthoredSourceIntakeRepository.class);
    var versionIdentities = mock(WorldAuthoredVersionIdentityRepository.class);
    var frozenTopologies = mock(WorldCanonicalFrozenTopologyRepository.class);
    var gameSession = mock(CanonicalGameInstanceLaunchAssociationClient.class);
    var gameDesign = mock(AuthoredWorldLaunchDescriptorClient.class);
    var service =
        new WorldCanonicalInstancePreparationAssemblyService(
            NAMESPACE,
            launchBindings,
            sourceIntakes,
            versionIdentities,
            frozenTopologies,
            gameSession,
            gameDesign);
    var selector =
        new WorldCanonicalInstancePreparationAssemblyService.Selector(
            TENANT_ID, INSTANCE_ID, CONTROL_REQUEST_ID);

    TransactionSynchronizationManager.setActualTransactionActive(true);
    assertThatThrownBy(() -> service.assemble(selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent committed reads");
    TransactionSynchronizationManager.clear();
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.assemble(selector))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("independent committed reads");

    verifyNoInteractions(
        launchBindings,
        sourceIntakes,
        versionIdentities,
        frozenTopologies,
        gameSession,
        gameDesign);
  }

  @Test
  void rejectsLegacyReleaseWithoutStoredTypedWorldSelectorBeforeRemoteReads() {
    var launchBindings = mock(WorldCompleteLaunchBindingRepository.class);
    var sourceIntakes = mock(WorldAuthoredSourceIntakeRepository.class);
    var versionIdentities = mock(WorldAuthoredVersionIdentityRepository.class);
    var frozenTopologies = mock(WorldCanonicalFrozenTopologyRepository.class);
    var gameSession = mock(CanonicalGameInstanceLaunchAssociationClient.class);
    var gameDesign = mock(AuthoredWorldLaunchDescriptorClient.class);
    var pair = binding('a');
    var descriptor = pair.descriptor();
    var intakeRequestId = UUID.randomUUID();
    var source = mock(WorldAuthoredSourceIntakeReceipt.class);
    when(source.targetNamespace()).thenReturn(NAMESPACE);
    when(source.intakeRequestId()).thenReturn(intakeRequestId);
    when(source.canonicalTenantId()).thenReturn(descriptor.canonicalTenantId());
    when(source.worldSlug()).thenReturn(descriptor.worldSlug());
    when(source.sourceOperationId()).thenReturn(descriptor.authoredWorldSourceOperationId());
    when(source.sourceEvidenceDigest()).thenReturn(descriptor.authoredWorldSourceEvidenceDigest());
    when(source.operationId()).thenReturn(UUID.randomUUID());
    when(source.localTenantKey()).thenReturn(41L);
    when(source.receiptDigest()).thenReturn("sha256:" + "3".repeat(64));
    when(source.requestDigest()).thenReturn("sha256:" + "4".repeat(64));
    var launchBinding =
        new WorldCompleteLaunchBindingReceipt(
            1,
            UUID.randomUUID(),
            NAMESPACE,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            descriptor.controlPlaneRequestId(),
            source,
            pair);
    var stored =
        new WorldCompleteLaunchBindingRepository.StoredBinding(
            launchBinding.operationId(),
            NAMESPACE,
            descriptor.canonicalTenantId(),
            descriptor.worldSlug(),
            descriptor.controlPlaneRequestId(),
            source.operationId(),
            intakeRequestId,
            source.localTenantKey(),
            source.sourceOperationId(),
            source.sourceEvidenceDigest(),
            source.receiptDigest(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            pair.releaseAttestation().evidenceDigest(),
            pair);
    when(launchBindings.read(
            NAMESPACE, descriptor.canonicalTenantId(), descriptor.controlPlaneRequestId()))
        .thenReturn(Optional.of(stored));
    when(sourceIntakes.read(NAMESPACE, intakeRequestId)).thenReturn(Optional.of(source));
    when(launchBindings.toReceipt(stored, source)).thenReturn(launchBinding);
    var service =
        new WorldCanonicalInstancePreparationAssemblyService(
            NAMESPACE,
            launchBindings,
            sourceIntakes,
            versionIdentities,
            frozenTopologies,
            gameSession,
            gameDesign);
    var selector =
        new WorldCanonicalInstancePreparationAssemblyService.Selector(
            descriptor.canonicalTenantId(), INSTANCE_ID, descriptor.controlPlaneRequestId());

    assertThatThrownBy(() -> service.assemble(selector))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("stored selected release selector");
    verifyNoInteractions(versionIdentities, frozenTopologies, gameSession, gameDesign);
  }

  @Test
  void acceptsOnlyTheExactGameSessionSelectorAndCompleteStoredPair() {
    var stored = binding('a');
    var request = gameSessionRequest(stored, INSTANCE_ID);
    var matching = gameSessionResult(request, stored);

    WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
        request, matching, stored);
    var worldEvidence =
        WorldCanonicalInstancePreparationAssemblyService.associationToWorldEvidence(
            request, matching);
    assertThat(worldEvidence.currentGameSessionStatus()).isEqualTo("RUNNING");
    assertThat(worldEvidence.currentGameSessionRowVersion()).isEqualTo(17L);
    assertThat(worldEvidence.canonicalGameInstanceId()).isEqualTo(INSTANCE_ID);
    assertThat(worldEvidence.playableStateScope()).isEqualTo("SHARED");
    assertThat(worldEvidence.publicProduction()).isTrue();

    var changedInstanceRequest = gameSessionRequest(stored, UUID.randomUUID());
    var changedInstanceResult = gameSessionResult(changedInstanceRequest, stored);
    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
                    request, changedInstanceResult, stored))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("Game Session association differs");

    var changedPair = binding('b');
    var changedPairRequest = gameSessionRequest(changedPair, INSTANCE_ID);
    var changedPairResult = gameSessionResult(changedPairRequest, changedPair);
    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
                    request, changedPairResult, stored))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("Game Session association differs");
  }

  @Test
  void rejectsNonSharedOrNonProductionGameSessionReadProjection() {
    var stored = binding('a');
    var request = gameSessionRequest(stored, INSTANCE_ID);
    var invalid = mock(CanonicalGameInstanceLaunchAssociationReadEvidence.Result.class);
    when(invalid.request()).thenReturn(request);
    when(invalid.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.ISOLATED);
    when(invalid.publicProduction()).thenReturn(true);
    when(invalid.launchBindingEvidence()).thenReturn(stored);

    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
                    request, invalid, stored))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("Game Session association differs");

    when(invalid.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(invalid.publicProduction()).thenReturn(false);
    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparationAssemblyService.requireGameSessionBinding(
                    request, invalid, stored))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("Game Session association differs");
  }

  @Test
  void independentlyAuthenticatedGameDesignPairMustMatchEveryStoredEvidenceField() {
    var stored = binding('a');
    WorldCanonicalInstancePreparationAssemblyService.requireSameCompletePair(stored, binding('a'));

    assertThatThrownBy(
            () ->
                WorldCanonicalInstancePreparationAssemblyService.requireSameCompletePair(
                    stored, binding('b')))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class)
        .hasMessageContaining("Authenticated Game Design complete launch pair differs");
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request gameSessionRequest(
      CompleteLaunchBindingEvidence binding, UUID instanceId) {
    var descriptor = binding.descriptor();
    var release = binding.releaseAttestation();
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.randomUUID(),
        descriptor.targetNamespace(),
        descriptor.canonicalTenantId(),
        descriptor.worldSlug(),
        instanceId,
        descriptor.controlPlaneRequestId(),
        descriptor.launchDescriptorId(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        release.evidenceDigest());
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Result gameSessionResult(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CompleteLaunchBindingEvidence binding) {
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
        request,
        UUID.randomUUID(),
        RealmEntryPolicy.StateScope.SHARED,
        true,
        CurrentGameInstanceStatus.RUNNING,
        17L,
        binding);
  }

  private static CompleteLaunchBindingEvidence binding(char digestCharacter) {
    UUID sourceOperationId = BINDING_SOURCE_OPERATION_ID;
    UUID tenantId = BINDING_TENANT_ID;
    String worldSlug = "assembly-world";
    String controlPlaneRequestId = "assembly-control";
    String sourceDigest = "sha256:" + "f".repeat(64);
    var request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            controlPlaneRequestId,
            tenantId,
            worldSlug,
            sourceOperationId,
            sourceDigest,
            71L,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    var descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "descriptor-assembly",
            83L,
            false,
            null,
            "{}",
            "generation-assembly",
            9L,
            89L,
            "release-assembly",
            false,
            null);
    String commitId = BINDING_COMMIT_ID;
    var participants =
        AuthoredWorldReleaseAttestationEvidence.requiredParticipantOrder().stream()
            .map(
                owner ->
                    new AuthoredWorldReleaseAttestationEvidence.Participant(
                        owner,
                        Long.toString(descriptor.versionId()),
                        false,
                        null,
                        commitId,
                        String.valueOf(digestCharacter).repeat(64),
                        AuthoredWorldReleaseAttestationEvidence.supportedParticipantDigestSchema(
                            owner),
                        "GAME_LOGIC".equals(owner),
                        "GAME_LOGIC".equals(owner) ? "sha256:" + "1".repeat(64) : null))
            .toList();
    var release =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            tenantId,
            BINDING_VERSION_ID,
            worldSlug,
            sourceOperationId,
            sourceDigest,
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:assembly",
            commitId,
            participants,
            "sha256:" + "2".repeat(64),
            1,
            List.of(),
            List.of(),
            List.of("LOOK"),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, release);
  }
}
