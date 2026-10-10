package net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorGrpcCodec.GetRequest;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateClient;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldVersionStateEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationClient;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence.CurrentGameInstanceStatus;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceDigest;
import net.firedevops.firemud.common.tenant.AuthoredWorldSourceEvidence;
import net.firedevops.firemud.common.world.WorldPublishedStartLocationEvidence;
import net.firedevops.firemud.gamedesign.v1.GetLaunchDescriptorRequest;
import net.firedevops.firemud.gamedesign.v1.VersionLifecycleState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class WorldCompleteLaunchBindingServiceTest {
  private static final String NAMESPACE = "firemud";
  private static final UUID TENANT = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID SOURCE_OPERATION =
      UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID INTAKE_REQUEST =
      UUID.fromString("44444444-4444-4444-8444-444444444444");
  private static final UUID INTAKE_OPERATION =
      UUID.fromString("55555555-5555-4555-8555-555555555555");
  private static final UUID BINDING_OPERATION =
      UUID.fromString("77777777-7777-4777-8777-777777777777");
  private static final UUID VERSION = UUID.fromString("88888888-8888-4888-8888-888888888888");
  private static final UUID OTHER_CANONICAL_VERSION =
      UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
  private static final UUID CURRENT_READ_ID =
      UUID.fromString("99999999-9999-4999-8999-999999999999");
  private static final UUID ADVANCED_READ_ID =
      UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final UUID OTHER_READ_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
  private static final UUID OTHER_SOURCE_OPERATION =
      UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
  private static final UUID OTHER_REGISTRATION_REQUEST =
      UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
  private static final String WORLD = "violet-wilds";
  private static final String CONTROL_PLANE_REQUEST = "world-launch-request-41";

  private final AuthoredWorldLaunchDescriptorClient client =
      mock(AuthoredWorldLaunchDescriptorClient.class);
  private final AuthoredWorldVersionStateClient versionStateClient =
      mock(AuthoredWorldVersionStateClient.class);
  private final CanonicalGameInstanceLaunchAssociationClient gameSessionClient =
      mock(CanonicalGameInstanceLaunchAssociationClient.class);
  private final WorldCompleteLaunchBindingRepository repository =
      mock(WorldCompleteLaunchBindingRepository.class);
  private final WorldAuthoredSourceIntakeRepository sourceRepository =
      mock(WorldAuthoredSourceIntakeRepository.class);
  private final RecordingTransactionManager transactionManager = new RecordingTransactionManager();
  private final WorldCompleteLaunchBindingService service =
      new WorldCompleteLaunchBindingService(
          client, repository, sourceRepository, transactionManager, NAMESPACE, versionStateClient);

  @AfterEach
  void clearTransactionState() {
    TransactionSynchronizationManager.clear();
    SessionContext.clear();
  }

  @Test
  void committedLaunchRequiresSameNamespaceGameSessionWithoutEndUserContextBeforeAccess() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    WorldCompleteLaunchBindingService committedService = committedLaunchService();

    assertThatThrownBy(
            () -> withoutPeer(() -> committedService.bindCommittedLaunch(fixture.request())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-design-service"),
                    () -> committedService.bindCommittedLaunch(fixture.request())))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-session-service"),
                    () -> committedService.bindCommittedLaunch(fixture.request())))
        .isInstanceOf(SecurityException.class);

    SessionContext.setContext(
        "99999999-9999-4999-8999-999999999999", List.of(), java.util.Map.of());
    assertThatThrownBy(
            () -> withGameSession(() -> committedService.bindCommittedLaunch(fixture.request())))
        .isInstanceOf(SecurityException.class);

    var wrongNamespaceRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            fixture.request().readRequestId(),
            "other",
            fixture.request().canonicalTenantId(),
            fixture.request().worldSlug(),
            fixture.request().gameInstanceUuid(),
            fixture.request().controlPlaneRequestId(),
            fixture.request().launchDescriptorId(),
            fixture.request().expectedDescriptorRequestDigest(),
            fixture.request().expectedDescriptorResultDigest(),
            fixture.request().expectedReleaseAttestationEvidenceDigest());
    SessionContext.clear();
    assertThatThrownBy(
            () ->
                withGameSession(() -> committedService.bindCommittedLaunch(wrongNamespaceRequest)))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(client, gameSessionClient, repository, sourceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchFailsClosedWhenGameSessionClientWasNotConfigured() {
    CommittedLaunchFixture fixture = committedLaunchFixture();

    assertThatThrownBy(() -> withGameSession(() -> service.bindCommittedLaunch(fixture.request())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no configured Game Session owner reader");
    verifyNoInteractions(client, gameSessionClient, repository, sourceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRefusesAmbientTransactionBeforeRemoteOrOwnerAccess() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> committedLaunchService().bindCommittedLaunch(fixture.request())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("outside ambient transactions");
    verifyNoInteractions(client, gameSessionClient, repository, sourceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRejectsChangedDigestAndV1ReleaseBeforeWorldOwnerWrite() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence v1Binding = evidence(source);
    var wrongDigestRequest =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            NAMESPACE,
            TENANT,
            WORLD,
            UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
            CONTROL_PLANE_REQUEST,
            v1Binding.descriptor().launchDescriptorId(),
            v1Binding.descriptor().requestDigest(),
            digest('a'),
            v1Binding.releaseAttestation().evidenceDigest());
    WorldCompleteLaunchBindingService committedService = committedLaunchService();
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(v1Binding);

    assertThatThrownBy(
            () -> withGameSession(() -> committedService.bindCommittedLaunch(wrongDigestRequest)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.RegistrationConflictException.class);

    var v1Request = committedAssociationRequest(v1Binding);
    assertThatThrownBy(() -> withGameSession(() -> committedService.bindCommittedLaunch(v1Request)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("full selected-release selector evidence");
    verify(gameSessionClient, never()).read(any());
    verifyNoInteractions(repository, sourceRepository);
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRejectsGameSessionPairThatDiffersFromGameDesignBeforeSourceOrWrite() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    CompleteLaunchBindingEvidence otherPair =
        committedPair(fixture.evidence().descriptor(), digest('8'));
    CanonicalGameInstanceLaunchAssociationReadEvidence.Result mismatchedAssociation =
        mock(CanonicalGameInstanceLaunchAssociationReadEvidence.Result.class);
    when(mismatchedAssociation.request()).thenReturn(fixture.request());
    when(mismatchedAssociation.playableStateScope()).thenReturn(RealmEntryPolicy.StateScope.SHARED);
    when(mismatchedAssociation.publicProduction()).thenReturn(true);
    when(mismatchedAssociation.launchBindingEvidence()).thenReturn(otherPair);
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(fixture.evidence());
    when(gameSessionClient.read(fixture.request())).thenReturn(mismatchedAssociation);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> committedLaunchService().bindCommittedLaunch(fixture.request())))
        .isInstanceOf(
            WorldCanonicalInstancePreparationAssemblyService.AssemblyRejectedException.class);
    verify(sourceRepository, never()).readBySource(any(), any(), any(), any(), any());
    verifyNoInteractions(repository);
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void exactCommittedLaunchRetryUsesTheOriginalPairWithoutAnotherOwnerWrite() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    WorldCompleteLaunchBindingService committedService = committedLaunchService();
    stubCommittedLaunchReads(fixture);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(fixture.stored()));
    when(repository.toReceipt(fixture.stored(), fixture.sourceReceipt()))
        .thenReturn(fixture.receipt());

    WorldCompleteLaunchBindingReceipt result =
        withGameSession(() -> committedService.bindCommittedLaunch(fixture.request()));

    assertThat(result).isSameAs(fixture.receipt());
    verify(client).getComplete(any(GetLaunchDescriptorRequest.class));
    verify(gameSessionClient).read(fixture.request());
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRetryRejectsAChangedRetainedReleasePairWithoutReplacement() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    CompleteLaunchBindingEvidence changedPair =
        committedPair(
            fixture.evidence().descriptor(),
            fixture.request().expectedReleaseAttestationEvidenceDigest());
    WorldCompleteLaunchBindingRepository.StoredBinding changedStored =
        stored(fixture.sourceReceipt(), changedPair);
    stubCommittedLaunchReads(fixture);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.of(changedStored));

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> committedLaunchService().bindCommittedLaunch(fixture.request())))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.RegistrationConflictException.class);
    verify(repository, never()).acceptFresh(any(), any(), any());
    verify(repository, never()).toReceipt(any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRequiresExactSourceIntakeBeforeWorldBindingOwnerAccess() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    stubCommittedLaunchReads(fixture);
    when(sourceRepository.readBySource(
            NAMESPACE,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            fixture.sourceReceipt().sourceEvidenceDigest()))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> committedLaunchService().bindCommittedLaunch(fixture.request())))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("no exact committed World source intake");
    verifyNoInteractions(repository);
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void committedLaunchRetainsThenIndependentlyReadsBackTheExactPairInFreshOwnerTransaction() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    WorldCompleteLaunchBindingService committedService = committedLaunchService();
    stubCommittedLaunchReads(fixture);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(fixture.stored()));
    when(repository.acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence()))
        .thenReturn(fixture.receipt());
    when(repository.toReceipt(fixture.stored(), fixture.sourceReceipt()))
        .thenReturn(fixture.receipt());

    WorldCompleteLaunchBindingReceipt result =
        withGameSession(() -> committedService.bindCommittedLaunch(fixture.request()));

    assertThat(result).isSameAs(fixture.receipt());
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    ArgumentCaptor<GetLaunchDescriptorRequest> descriptorRead =
        ArgumentCaptor.forClass(GetLaunchDescriptorRequest.class);
    verify(client).getComplete(descriptorRead.capture());
    assertThat(descriptorRead.getValue().getRequestId())
        .isNotEqualTo(fixture.request().readRequestId().toString());
    assertThat(descriptorRead.getValue().getCanonicalTenantId()).isEqualTo(TENANT.toString());
    assertThat(descriptorRead.getValue().getWorldSlug()).isEqualTo(WORLD);
    assertThat(descriptorRead.getValue().getControlPlaneRequestId())
        .isEqualTo(CONTROL_PLANE_REQUEST);
    assertThat(descriptorRead.getValue().getExpectedRequestDigest())
        .isEqualTo(fixture.request().expectedDescriptorRequestDigest());
    assertThat(descriptorRead.getValue().getExpectedResultDigest())
        .isEqualTo(fixture.request().expectedDescriptorResultDigest());
    InOrder committedReadOrder = inOrder(client, gameSessionClient, sourceRepository, repository);
    committedReadOrder.verify(client).getComplete(any(GetLaunchDescriptorRequest.class));
    committedReadOrder.verify(gameSessionClient).read(fixture.request());
    committedReadOrder
        .verify(sourceRepository)
        .readBySource(
            NAMESPACE,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            fixture.sourceReceipt().sourceEvidenceDigest());
    committedReadOrder.verify(repository).read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST);
    committedReadOrder
        .verify(repository)
        .acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence());
    committedReadOrder.verify(repository).read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST);
    committedReadOrder
        .verify(sourceRepository)
        .readBySource(
            NAMESPACE,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            fixture.sourceReceipt().sourceEvidenceDigest());
    committedReadOrder.verify(repository).toReceipt(fixture.stored(), fixture.sourceReceipt());
    verify(gameSessionClient).read(fixture.request());
    verify(repository).acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence());
    verify(repository).toReceipt(fixture.stored(), fixture.sourceReceipt());
  }

  @Test
  void committedLaunchRecoversLostCommitAcknowledgmentOnlyForTheSamePair() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    stubCommittedLaunchReads(fixture);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(fixture.stored()));
    when(repository.acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence()))
        .thenReturn(fixture.receipt());
    when(repository.toReceipt(fixture.stored(), fixture.sourceReceipt()))
        .thenReturn(fixture.receipt());
    transactionManager.loseCommitAcknowledgment = true;

    WorldCompleteLaunchBindingReceipt recovered =
        withGameSession(() -> committedLaunchService().bindCommittedLaunch(fixture.request()));

    assertThat(recovered).isSameAs(fixture.receipt());
    verify(repository).acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence());
    verify(repository).toReceipt(fixture.stored(), fixture.sourceReceipt());
    assertThat(transactionManager.commitFailureThrown).isTrue();
  }

  @Test
  void committedLaunchRejectsChangedPairDuringLostCommitAcknowledgmentRecovery() {
    CommittedLaunchFixture fixture = committedLaunchFixture();
    CompleteLaunchBindingEvidence changedPair =
        committedPair(
            fixture.evidence().descriptor(),
            fixture.request().expectedReleaseAttestationEvidenceDigest());
    WorldCompleteLaunchBindingRepository.StoredBinding changedStored =
        stored(fixture.sourceReceipt(), changedPair);
    stubCommittedLaunchReads(fixture);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(changedStored));
    when(repository.acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence()))
        .thenReturn(fixture.receipt());
    transactionManager.loseCommitAcknowledgment = true;

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> committedLaunchService().bindCommittedLaunch(fixture.request())))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.RegistrationConflictException.class);
    verify(repository).acceptFresh(NAMESPACE, fixture.sourceReceipt(), fixture.evidence());
    verify(repository, never()).toReceipt(any(), any());
    assertThat(transactionManager.commitFailureThrown).isTrue();
  }

  @Test
  void requiresExactSameNamespaceGameSessionPeerBeforeOwnerOrGameDesignAccess() {
    GetRequest request = request(evidence(source()));

    assertThatThrownBy(() -> withoutPeer(() -> service.bind(request)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () -> withPeer(peer(NAMESPACE, "game-design-service"), () -> service.bind(request)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () -> withPeer(peer("other", "game-session-service"), () -> service.bind(request)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () -> withoutPeer(() -> service.readCurrentVersionState(request, CURRENT_READ_ID)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer(NAMESPACE, "game-design-service"),
                    () -> service.readCurrentVersionState(request, CURRENT_READ_ID)))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withPeer(
                    peer("other", "game-session-service"),
                    () -> service.readCurrentVersionState(request, CURRENT_READ_ID)))
        .isInstanceOf(SecurityException.class);
    AuthoredWorldLaunchDescriptorEvidence.Request original = request.expectedRequest();
    AuthoredWorldLaunchDescriptorEvidence.Request wrongNamespace =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            "other",
            original.controlPlaneRequestId(),
            original.canonicalTenantId(),
            original.worldSlug(),
            original.authoredWorldSourceOperationId(),
            original.authoredWorldSourceEvidenceDigest(),
            original.gameTemplateId(),
            original.requestedScriptPatchVersionPresent(),
            original.requestedScriptPatchVersion(),
            original.sourceVersionIdPresent(),
            original.sourceVersionId(),
            original.targetVersionIdPresent(),
            original.targetVersionId(),
            original.requestedRuntimeFlagsJsonPresent(),
            original.requestedRuntimeFlagsJson());
    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.bind(
                            new GetRequest(UUID.randomUUID(), wrongNamespace, digest('a')))))
        .isInstanceOf(SecurityException.class);
    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.readCurrentVersionState(
                            new GetRequest(UUID.randomUUID(), wrongNamespace, digest('a')),
                            CURRENT_READ_ID)))
        .isInstanceOf(SecurityException.class);
    verifyNoInteractions(client, versionStateClient, repository, sourceRepository);
  }

  @Test
  void retainsCompletePairThenIndependentlyReadsBackTheSameOwnerReceipt() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    CompleteLaunchBindingEvidence evidence = evidence(source);
    GetRequest request = request(evidence);
    WorldCompleteLaunchBindingReceipt receipt = bindingReceipt(sourceReceipt, evidence);
    WorldCompleteLaunchBindingRepository.StoredBinding stored = stored(receipt);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(client.getComplete(any())).thenReturn(evidence);
    when(sourceRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(sourceReceipt));
    when(repository.acceptFresh(NAMESPACE, sourceReceipt, evidence)).thenReturn(receipt);
    when(repository.toReceipt(stored, sourceReceipt)).thenReturn(receipt);

    WorldCompleteLaunchBindingReceipt result = withGameSession(() -> service.bind(request));

    assertThat(result).isEqualTo(receipt);
    assertThat(transactionManager.startedWith.getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(transactionManager.startedWith.getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    assertThat(transactionManager.startedWith.isReadOnly()).isFalse();
    verify(client).getComplete(any());
    verify(repository).acceptFresh(NAMESPACE, sourceReceipt, evidence);
    verify(repository).toReceipt(stored, sourceReceipt);
  }

  @Test
  void exactRetryReadsTheStoredPairBeforeLocalSourceAndDoesNotCallGameDesign() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    CompleteLaunchBindingEvidence evidence = evidence(source);
    GetRequest request = request(evidence);
    WorldCompleteLaunchBindingReceipt receipt = bindingReceipt(sourceReceipt, evidence);
    WorldCompleteLaunchBindingRepository.StoredBinding stored = stored(receipt);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST)).thenReturn(Optional.of(stored));
    when(sourceRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(sourceReceipt));
    when(repository.toReceipt(stored, sourceReceipt)).thenReturn(receipt);

    WorldCompleteLaunchBindingReceipt result = withGameSession(() -> service.bind(request));

    assertThat(result).isEqualTo(receipt);
    verify(repository).read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST);
    verify(client, never()).getComplete(any());
    verify(repository, never()).acceptFresh(any(), any(), any());
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void changedExpectedResultConflictsBeforeSourceLookupOrGameDesign() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    CompleteLaunchBindingEvidence evidence = evidence(source);
    WorldCompleteLaunchBindingReceipt receipt = bindingReceipt(sourceReceipt, evidence);
    WorldCompleteLaunchBindingRepository.StoredBinding stored = stored(receipt);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST)).thenReturn(Optional.of(stored));
    GetRequest changed =
        new GetRequest(UUID.randomUUID(), evidence.descriptor().request(), digest('b'));

    assertThatThrownBy(() -> withGameSession(() -> service.bind(changed)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.RegistrationConflictException.class);
    verify(repository).read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST);
    verifyNoInteractions(client, sourceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void missingOrSwappedCompletePairCannotReachWorldPersistence() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence expectedEvidence = evidence(source);
    CompleteLaunchBindingEvidence otherEvidence =
        evidence(source, "another-world-launch-request", 42L);
    assertThatThrownBy(
            () ->
                new CompleteLaunchBindingEvidence(
                    expectedEvidence.descriptor(), otherEvidence.releaseAttestation()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CompleteLaunchBindingEvidence(null, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new CompleteLaunchBindingEvidence(expectedEvidence.descriptor(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new CompleteLaunchBindingEvidence(null, expectedEvidence.releaseAttestation()))
        .isInstanceOf(NullPointerException.class);

    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST)).thenReturn(Optional.empty());
    when(client.getComplete(any())).thenReturn(null);

    assertThatThrownBy(() -> withGameSession(() -> service.bind(request(expectedEvidence))))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("no complete launch binding");
    verify(repository, never()).acceptFresh(any(), any(), any());
    verifyNoInteractions(sourceRepository);
    assertThat(transactionManager.startedWith).isNull();
  }

  @Test
  void refusesAmbientTransactionBeforeReadingOrCallingGameDesign() {
    CompleteLaunchBindingEvidence evidence = evidence(source());
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> withGameSession(() -> service.bind(request(evidence))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(evidence), CURRENT_READ_ID)))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(client, versionStateClient, repository, sourceRepository);
  }

  @Test
  void recoversACommitAcknowledgmentLossByReadingTheSameOwnerOperation() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    CompleteLaunchBindingEvidence evidence = evidence(source);
    GetRequest request = request(evidence);
    WorldCompleteLaunchBindingReceipt receipt = bindingReceipt(sourceReceipt, evidence);
    WorldCompleteLaunchBindingRepository.StoredBinding stored = stored(receipt);
    AtomicReference<WorldCompleteLaunchBindingRepository.StoredBinding> committed =
        new AtomicReference<>();
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST))
        .thenAnswer(
            invocation -> {
              WorldCompleteLaunchBindingRepository.StoredBinding value = committed.get();
              return value == null ? Optional.empty() : Optional.of(value);
            });
    when(client.getComplete(any())).thenReturn(evidence);
    when(sourceRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(sourceReceipt));
    when(repository.acceptFresh(NAMESPACE, sourceReceipt, evidence))
        .thenAnswer(
            invocation -> {
              committed.set(stored);
              return receipt;
            });
    when(repository.toReceipt(stored, sourceReceipt)).thenReturn(receipt);
    transactionManager.loseCommitAcknowledgment = true;

    WorldCompleteLaunchBindingReceipt recovered = withGameSession(() -> service.bind(request));

    assertThat(recovered.operationId()).isEqualTo(receipt.operationId());
    assertThat(recovered.evidence()).isEqualTo(evidence);
    verify(client).getComplete(any());
    verify(repository).acceptFresh(NAMESPACE, sourceReceipt, evidence);
    assertThat(transactionManager.commitFailureThrown).isTrue();
  }

  @Test
  void readsPublishedCurrentVersionFromTheCommittedPairAndDescriptorVersionId() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    WorldCompleteLaunchBindingReceipt original = stubCommittedBinding(source, binding);
    AuthoredWorldVersionStateEvidence current =
        currentVersionState(
            source,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(current);

    AuthoredWorldVersionStateEvidence result =
        withGameSession(() -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID));

    assertThat(result).isEqualTo(current);
    ArgumentCaptor<AuthoredWorldVersionStateEvidence.Request> requestCaptor =
        ArgumentCaptor.forClass(AuthoredWorldVersionStateEvidence.Request.class);
    InOrder readOrder = inOrder(repository, sourceRepository, versionStateClient);
    readOrder.verify(repository).read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST);
    readOrder
        .verify(sourceRepository)
        .readBySource(NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest());
    readOrder.verify(repository).toReceipt(any(), any());
    readOrder.verify(versionStateClient).read(requestCaptor.capture());
    AuthoredWorldVersionStateEvidence.Request currentRequest = requestCaptor.getValue();
    assertThat(currentRequest.targetNamespace()).isEqualTo(original.targetNamespace());
    assertThat(currentRequest.canonicalTenantId()).isEqualTo(original.canonicalTenantId());
    assertThat(currentRequest.worldSlug()).isEqualTo(original.worldSlug());
    assertThat(currentRequest.sourceOperationId()).isEqualTo(SOURCE_OPERATION);
    assertThat(currentRequest.expectedSourceEvidenceDigest()).isEqualTo(source.evidenceDigest());
    assertThat(currentRequest.versionId()).isEqualTo(binding.descriptor().versionId());
    assertThat(currentRequest.versionId())
        .isNotEqualTo(original.sourceIntakeReceipt().localTenantKey());
    assertThat(currentRequest.readRequestId()).isEqualTo(CURRENT_READ_ID);
    assertThat(original.evidence()).isEqualTo(binding);
    assertThat(original.operationId()).isEqualTo(BINDING_OPERATION);
    assertThat(transactionManager.startedWith).isNull();
    verify(client, never()).getComplete(any());
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void acceptsActiveCurrentVersionAtTheDescriptorEpoch() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);
    AuthoredWorldVersionStateEvidence current =
        currentVersionState(
            source,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(current);

    AuthoredWorldVersionStateEvidence result =
        withGameSession(() -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID));

    assertThat(result).isEqualTo(current);
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void repeatedCurrentReadsObserveVersionAdvanceAndRejectTheStaleDescriptorPair() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    WorldCompleteLaunchBindingReceipt original = stubCommittedBinding(source, binding);
    AuthoredWorldVersionStateEvidence first =
        currentVersionState(
            source,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    AuthoredWorldVersionStateEvidence advanced =
        currentVersionState(
            source,
            ADVANCED_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_ACTIVE,
            binding.descriptor().versionStateEpoch() + 1);
    when(versionStateClient.read(any())).thenReturn(first, advanced);

    AuthoredWorldVersionStateEvidence firstRead =
        withGameSession(() -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID));
    assertThat(firstRead).isEqualTo(first);
    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), ADVANCED_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("epoch differs");

    ArgumentCaptor<AuthoredWorldVersionStateEvidence.Request> requestCaptor =
        ArgumentCaptor.forClass(AuthoredWorldVersionStateEvidence.Request.class);
    verify(versionStateClient, times(2)).read(requestCaptor.capture());
    assertThat(requestCaptor.getAllValues())
        .extracting(AuthoredWorldVersionStateEvidence.Request::readRequestId)
        .containsExactly(CURRENT_READ_ID, ADVANCED_READ_ID);
    assertThat(original.evidence()).isEqualTo(binding);
    assertThat(original.operationId()).isEqualTo(BINDING_OPERATION);
    verify(client, never()).getComplete(any());
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void rejectsCurrentEvidenceForAnotherAuthoredSource() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);
    AuthoredWorldSourceEvidence otherSource =
        source("other-world", OTHER_REGISTRATION_REQUEST, OTHER_SOURCE_OPERATION);
    AuthoredWorldVersionStateEvidence swappedSource =
        currentVersionState(
            otherSource,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(swappedSource);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("exact committed World source");
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void rejectsAChangedVersionSelectorInCurrentOwnerEvidence() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);
    AuthoredWorldVersionStateEvidence swappedSelector =
        currentVersionState(
            source,
            CURRENT_READ_ID,
            binding.descriptor().versionId() + 1,
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(swappedSelector);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("exact committed World source");
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void rejectsCurrentEvidenceWithCanonicalVersionUuidDifferentFromReleaseAttestation() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);
    UUID committedCanonicalVersionId = binding.releaseAttestation().canonicalVersionId();
    AuthoredWorldVersionStateEvidence expected =
        currentVersionState(
            source,
            committedCanonicalVersionId,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    AuthoredWorldVersionStateEvidence substituted =
        currentVersionState(
            source,
            OTHER_CANONICAL_VERSION,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_PUBLISHED,
            binding.descriptor().versionStateEpoch());
    assertThat(substituted.request()).isEqualTo(expected.request());
    assertThat(substituted.sourceEvidence()).isEqualTo(expected.sourceEvidence());
    assertThat(substituted.versionState()).isEqualTo(expected.versionState());
    assertThat(substituted.versionStateEpoch()).isEqualTo(expected.versionStateEpoch());
    assertThat(substituted.canonicalVersionId()).isNotEqualTo(committedCanonicalVersionId);
    when(versionStateClient.read(any())).thenReturn(substituted);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("canonical version UUID differs");
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void changedBindingRequestIsRejectedBeforeReadingCurrentOwnerEvidence() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);
    GetRequest substituted =
        new GetRequest(UUID.randomUUID(), binding.descriptor().request(), digest('b'));

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(substituted, CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.RegistrationConflictException.class);
    verifyNoInteractions(versionStateClient);
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void requiresAnAlreadyCommittedPairWithoutRegisteringOne() {
    CompleteLaunchBindingEvidence binding = evidence(source());
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("requires a committed launch binding");
    verifyNoInteractions(client, versionStateClient, sourceRepository);
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void deniesDraftRetiredAndUnavailableCurrentVersionEvidenceWithoutChangingThePair() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    WorldCompleteLaunchBindingReceipt original = stubCommittedBinding(source, binding);
    AuthoredWorldVersionStateEvidence draft =
        currentVersionState(
            source,
            CURRENT_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_DRAFT,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(draft);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), CURRENT_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("not PUBLISHED or ACTIVE");

    AuthoredWorldVersionStateEvidence retired =
        currentVersionState(
            source,
            OTHER_READ_ID,
            binding.descriptor().versionId(),
            VersionLifecycleState.VERSION_LIFECYCLE_STATE_RETIRED,
            binding.descriptor().versionStateEpoch());
    when(versionStateClient.read(any())).thenReturn(retired);
    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), OTHER_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("not PUBLISHED or ACTIVE");

    when(versionStateClient.read(any()))
        .thenThrow(new IllegalStateException("Game Design is unavailable"));
    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), ADVANCED_READ_ID)))
        .isInstanceOf(WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException.class)
        .hasMessageContaining("unavailable or invalid");
    assertThat(original.evidence()).isEqualTo(binding);
    verify(repository, never()).acceptFresh(any(), any(), any());
  }

  @Test
  void rejectsReadCorrelationIdsReusedFromSourceOrRegistration() {
    AuthoredWorldSourceEvidence source = source();
    CompleteLaunchBindingEvidence binding = evidence(source);
    stubCommittedBinding(source, binding);

    assertThatThrownBy(
            () ->
                withGameSession(
                    () -> service.readCurrentVersionState(request(binding), SOURCE_OPERATION)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separate from source and registration IDs");
    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.readCurrentVersionState(
                            request(binding), source.registrationRequestId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separate from source and registration IDs");
    assertThatThrownBy(
            () ->
                withGameSession(
                    () ->
                        service.readCurrentVersionState(
                            request(binding), request(binding).requestId())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("separate from source and registration IDs");
    verifyNoInteractions(versionStateClient);
  }

  private WorldCompleteLaunchBindingService committedLaunchService() {
    return new WorldCompleteLaunchBindingService(
        client,
        repository,
        sourceRepository,
        transactionManager,
        NAMESPACE,
        versionStateClient,
        gameSessionClient);
  }

  private void stubCommittedLaunchReads(CommittedLaunchFixture fixture) {
    when(client.getComplete(any(GetLaunchDescriptorRequest.class))).thenReturn(fixture.evidence());
    when(gameSessionClient.read(fixture.request())).thenReturn(fixture.associationResult());
    when(sourceRepository.readBySource(
            NAMESPACE,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            fixture.sourceReceipt().sourceEvidenceDigest()))
        .thenReturn(Optional.of(fixture.sourceReceipt()));
  }

  private static CommittedLaunchFixture committedLaunchFixture() {
    AuthoredWorldSourceEvidence source = source();
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    AuthoredWorldLaunchDescriptorEvidence descriptor = evidence(source).descriptor();
    CompleteLaunchBindingEvidence pair = committedPair(descriptor, digest('7'));
    var request = committedAssociationRequest(pair);
    var associationResult =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Result(
            request,
            UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
            RealmEntryPolicy.StateScope.SHARED,
            true,
            CurrentGameInstanceStatus.RUNNING,
            7L,
            pair);
    WorldCompleteLaunchBindingReceipt receipt = mock(WorldCompleteLaunchBindingReceipt.class);
    when(receipt.targetNamespace()).thenReturn(NAMESPACE);
    when(receipt.canonicalTenantId()).thenReturn(TENANT);
    when(receipt.worldSlug()).thenReturn(WORLD);
    when(receipt.controlPlaneRequestId()).thenReturn(CONTROL_PLANE_REQUEST);
    when(receipt.sourceIntakeReceipt()).thenReturn(sourceReceipt);
    when(receipt.evidence()).thenReturn(pair);
    when(receipt.descriptor()).thenReturn(descriptor);

    WorldCompleteLaunchBindingRepository.StoredBinding stored =
        new WorldCompleteLaunchBindingRepository.StoredBinding(
            BINDING_OPERATION,
            NAMESPACE,
            TENANT,
            WORLD,
            CONTROL_PLANE_REQUEST,
            sourceReceipt.operationId(),
            sourceReceipt.intakeRequestId(),
            sourceReceipt.localTenantKey(),
            sourceReceipt.sourceOperationId(),
            sourceReceipt.sourceEvidenceDigest(),
            sourceReceipt.receiptDigest(),
            descriptor.requestDigest(),
            descriptor.resultDigest(),
            pair.releaseAttestation().evidenceDigest(),
            pair);
    return new CommittedLaunchFixture(
        source, sourceReceipt, pair, request, associationResult, stored, receipt);
  }

  private static WorldCompleteLaunchBindingRepository.StoredBinding stored(
      WorldAuthoredSourceIntakeReceipt sourceReceipt, CompleteLaunchBindingEvidence pair) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = pair.descriptor();
    return new WorldCompleteLaunchBindingRepository.StoredBinding(
        BINDING_OPERATION,
        NAMESPACE,
        TENANT,
        WORLD,
        CONTROL_PLANE_REQUEST,
        sourceReceipt.operationId(),
        sourceReceipt.intakeRequestId(),
        sourceReceipt.localTenantKey(),
        sourceReceipt.sourceOperationId(),
        sourceReceipt.sourceEvidenceDigest(),
        sourceReceipt.receiptDigest(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        pair.releaseAttestation().evidenceDigest(),
        pair);
  }

  // The closed pair is mocked in these component tests; this is not authenticated owner-storage
  // proof.
  private static CompleteLaunchBindingEvidence committedPair(
      AuthoredWorldLaunchDescriptorEvidence descriptor, String releaseEvidenceDigest) {
    AuthoredWorldReleaseAttestationEvidence release =
        mock(AuthoredWorldReleaseAttestationEvidence.class);
    WorldPublishedStartLocationEvidence selector = mock(WorldPublishedStartLocationEvidence.class);
    WorldPublishedStartLocationEvidence.Request selectorRequest =
        mock(WorldPublishedStartLocationEvidence.Request.class);
    when(selector.request()).thenReturn(selectorRequest);
    when(selectorRequest.intakeRequestId()).thenReturn(INTAKE_REQUEST);
    when(release.schemaVersion())
        .thenReturn(AuthoredWorldReleaseAttestationEvidence.SELECTOR_SCHEMA_VERSION);
    when(release.evidenceDigest()).thenReturn(releaseEvidenceDigest);
    when(release.worldStartLocationEvidence()).thenReturn(selector);
    CompleteLaunchBindingEvidence pair = mock(CompleteLaunchBindingEvidence.class);
    when(pair.descriptor()).thenReturn(descriptor);
    when(pair.releaseAttestation()).thenReturn(release);
    return pair;
  }

  private static CanonicalGameInstanceLaunchAssociationReadEvidence.Request
      committedAssociationRequest(CompleteLaunchBindingEvidence evidence) {
    return new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
        NAMESPACE,
        evidence.descriptor().canonicalTenantId(),
        evidence.descriptor().worldSlug(),
        UUID.fromString("abababab-abab-4bab-8bab-abababababab"),
        evidence.descriptor().controlPlaneRequestId(),
        evidence.descriptor().launchDescriptorId(),
        evidence.descriptor().requestDigest(),
        evidence.descriptor().resultDigest(),
        evidence.releaseAttestation().evidenceDigest());
  }

  private record CommittedLaunchFixture(
      AuthoredWorldSourceEvidence source,
      WorldAuthoredSourceIntakeReceipt sourceReceipt,
      CompleteLaunchBindingEvidence evidence,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request request,
      CanonicalGameInstanceLaunchAssociationReadEvidence.Result associationResult,
      WorldCompleteLaunchBindingRepository.StoredBinding stored,
      WorldCompleteLaunchBindingReceipt receipt) {}

  private WorldCompleteLaunchBindingReceipt stubCommittedBinding(
      AuthoredWorldSourceEvidence source, CompleteLaunchBindingEvidence evidence) {
    WorldAuthoredSourceIntakeReceipt sourceReceipt = sourceReceipt(source);
    WorldCompleteLaunchBindingReceipt receipt = bindingReceipt(sourceReceipt, evidence);
    WorldCompleteLaunchBindingRepository.StoredBinding stored = stored(receipt);
    when(repository.read(NAMESPACE, TENANT, CONTROL_PLANE_REQUEST)).thenReturn(Optional.of(stored));
    when(sourceRepository.readBySource(
            NAMESPACE, TENANT, WORLD, SOURCE_OPERATION, source.evidenceDigest()))
        .thenReturn(Optional.of(sourceReceipt));
    when(repository.toReceipt(stored, sourceReceipt)).thenReturn(receipt);
    return receipt;
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldSourceEvidence source,
      UUID readId,
      long versionId,
      VersionLifecycleState state,
      long epoch) {
    return currentVersionState(source, VERSION, readId, versionId, state, epoch);
  }

  private static AuthoredWorldVersionStateEvidence currentVersionState(
      AuthoredWorldSourceEvidence source,
      UUID canonicalVersionId,
      UUID readId,
      long versionId,
      VersionLifecycleState state,
      long epoch) {
    AuthoredWorldVersionStateEvidence.Request request =
        new AuthoredWorldVersionStateEvidence.Request(
            1,
            source.targetNamespace(),
            readId,
            source.canonicalTenantId(),
            source.worldSlug(),
            source.operationId(),
            source.evidenceDigest(),
            versionId);
    return AuthoredWorldVersionStateEvidence.create(
        request, source, canonicalVersionId, state, epoch);
  }

  private static WorldCompleteLaunchBindingRepository.StoredBinding stored(
      WorldCompleteLaunchBindingReceipt receipt) {
    AuthoredWorldLaunchDescriptorEvidence descriptor = receipt.descriptor();
    return new WorldCompleteLaunchBindingRepository.StoredBinding(
        receipt.operationId(),
        receipt.targetNamespace(),
        receipt.canonicalTenantId(),
        receipt.worldSlug(),
        receipt.controlPlaneRequestId(),
        receipt.sourceIntakeReceipt().operationId(),
        receipt.sourceIntakeReceipt().intakeRequestId(),
        receipt.sourceIntakeReceipt().localTenantKey(),
        receipt.sourceIntakeReceipt().sourceOperationId(),
        receipt.sourceIntakeReceipt().sourceEvidenceDigest(),
        receipt.sourceIntakeReceipt().receiptDigest(),
        descriptor.requestDigest(),
        descriptor.resultDigest(),
        receipt.evidence().releaseAttestation().evidenceDigest(),
        receipt.evidence());
  }

  private static WorldCompleteLaunchBindingReceipt bindingReceipt(
      WorldAuthoredSourceIntakeReceipt sourceReceipt, CompleteLaunchBindingEvidence evidence) {
    return new WorldCompleteLaunchBindingReceipt(
        1,
        BINDING_OPERATION,
        NAMESPACE,
        TENANT,
        WORLD,
        CONTROL_PLANE_REQUEST,
        sourceReceipt,
        evidence);
  }

  private static GetRequest request(CompleteLaunchBindingEvidence evidence) {
    return new GetRequest(
        UUID.fromString("66666666-6666-4666-8666-666666666666"),
        evidence.descriptor().request(),
        evidence.descriptor().resultDigest());
  }

  private static CompleteLaunchBindingEvidence evidence(AuthoredWorldSourceEvidence source) {
    return evidence(source, CONTROL_PLANE_REQUEST, 41L);
  }

  private static CompleteLaunchBindingEvidence evidence(
      AuthoredWorldSourceEvidence source, String controlPlaneRequestId, long gameTemplateId) {
    AuthoredWorldLaunchDescriptorEvidence.Request request =
        new AuthoredWorldLaunchDescriptorEvidence.Request(
            NAMESPACE,
            controlPlaneRequestId,
            TENANT,
            WORLD,
            SOURCE_OPERATION,
            source.evidenceDigest(),
            gameTemplateId,
            false,
            null,
            false,
            null,
            false,
            null,
            false,
            null);
    AuthoredWorldLaunchDescriptorEvidence descriptor =
        AuthoredWorldLaunchDescriptorEvidence.create(
            request,
            "launch-descriptor-" + gameTemplateId,
            42L,
            false,
            null,
            "{}",
            "generation-config-41",
            7L,
            51L,
            "release-bundle-51",
            false,
            null);
    String commitId = "commit-41";
    List<AuthoredWorldReleaseAttestationEvidence.Participant> participants =
        List.of(
            participant("WORLD_MANAGEMENT", 3, "a", false),
            participant("ENTITY_MANAGEMENT", 2, "b", false),
            participant("GAME_LOGIC", 1, "c", true),
            participant("AUTOMATION_SCRIPTING", 5, "d", false),
            participant("GAME_DESIGN_CONTROL_PLANE", 1, "e", false));
    AuthoredWorldReleaseAttestationEvidence attestation =
        AuthoredWorldReleaseAttestationEvidence.create(
            NAMESPACE,
            descriptor.resultDigest(),
            TENANT,
            VERSION,
            WORLD,
            SOURCE_OPERATION,
            source.evidenceDigest(),
            descriptor.launchDescriptorId(),
            descriptor.publishedReleaseBundleRef(),
            descriptor.versionStateEpoch(),
            "publish:owner:launch-41",
            commitId,
            participants,
            digest('f'),
            1,
            List.of(),
            List.of(),
            List.of("look"),
            descriptor.generationConfigRevision());
    return new CompleteLaunchBindingEvidence(descriptor, attestation);
  }

  private static AuthoredWorldReleaseAttestationEvidence.Participant participant(
      String owner, int schema, String contentHex, boolean hasAbilityDigest) {
    return new AuthoredWorldReleaseAttestationEvidence.Participant(
        owner,
        "42",
        false,
        null,
        "commit-41",
        contentHex.repeat(64),
        schema,
        hasAbilityDigest,
        hasAbilityDigest ? digest('c') : null);
  }

  private static WorldAuthoredSourceIntakeReceipt sourceReceipt(
      AuthoredWorldSourceEvidence source) {
    String requestDigest =
        WorldAuthoredSourceIntakeDigest.requestDigest(NAMESPACE, INTAKE_REQUEST, source);
    return new WorldAuthoredSourceIntakeReceipt(
        1,
        NAMESPACE,
        INTAKE_REQUEST,
        INTAKE_OPERATION,
        TENANT,
        WORLD,
        SOURCE_OPERATION,
        source.evidenceDigest(),
        requestDigest,
        WorldAuthoredSourceIntakeDigest.receiptDigest(
            NAMESPACE, INTAKE_OPERATION, requestDigest, source, 90_041L),
        90_041L,
        source);
  }

  private static AuthoredWorldSourceEvidence source() {
    return source(WORLD, UUID.fromString("11111111-1111-4111-8111-111111111111"), SOURCE_OPERATION);
  }

  private static AuthoredWorldSourceEvidence source(
      String worldSlug, UUID registrationRequest, UUID sourceOperation) {
    String requestDigest =
        AuthoredWorldSourceDigest.requestDigest(
            NAMESPACE, registrationRequest, TENANT, "north-star", worldSlug, "Café 🐉");
    String evidenceDigest =
        AuthoredWorldSourceDigest.evidenceDigest(
            NAMESPACE,
            registrationRequest,
            sourceOperation,
            requestDigest,
            TENANT,
            "north-star",
            worldSlug,
            "Café 🐉",
            42L,
            "legacy-game-tenant-42",
            "NEW_GAME_ROW");
    return new AuthoredWorldSourceEvidence(
        1,
        NAMESPACE,
        registrationRequest,
        sourceOperation,
        requestDigest,
        TENANT,
        "north-star",
        worldSlug,
        "Café 🐉",
        42L,
        "legacy-game-tenant-42",
        "NEW_GAME_ROW",
        evidenceDigest);
  }

  private static String digest(char value) {
    return "sha256:" + String.valueOf(value).repeat(64);
  }

  private static GrpcPeerIdentity peer(String namespace, String service) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static <T> T withGameSession(Supplier<T> action) {
    return withPeer(peer(NAMESPACE, "game-session-service"), action);
  }

  private static <T> T withPeer(GrpcPeerIdentity peer, Supplier<T> action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      return action.get();
    } finally {
      context.detach(previous);
    }
  }

  private static <T> T withoutPeer(Supplier<T> action) {
    Context previous = Context.ROOT.attach();
    try {
      return action.get();
    } finally {
      Context.ROOT.detach(previous);
    }
  }

  private static final class RecordingTransactionManager
      implements org.springframework.transaction.PlatformTransactionManager {
    private TransactionDefinition startedWith;
    private boolean loseCommitAcknowledgment;
    private boolean commitFailureThrown;

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      startedWith = definition;
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());
      TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
          definition.getIsolationLevel());
      return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
      if (loseCommitAcknowledgment) {
        loseCommitAcknowledgment = false;
        commitFailureThrown = true;
        throw new IllegalStateException("simulated lost commit acknowledgment");
      }
    }

    @Override
    public void rollback(TransactionStatus status) {
      TransactionSynchronizationManager.clear();
    }
  }
}
