package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldLaunchDescriptorEvidence;
import net.firedevops.firemud.common.gamedesign.AuthoredWorldReleaseAttestationEvidence;
import net.firedevops.firemud.common.gamedesign.CompleteLaunchBindingEvidence;
import net.firedevops.firemud.common.gamesession.CanonicalGameInstanceLaunchAssociationReadEvidence;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.common.world.CanonicalWorldInstancePreparationGrpcCodec;
import net.firedevops.firemud.common.world.RoomTemplateRef;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence.Request;
import net.firedevops.firemud.worldmanagement.tenant.WorldAuthoredVersionIdentityReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalFrozenTopology;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation.GameSessionReadEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceAssociation.GameSessionReadRequest;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Input;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparation.Result;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationAssemblyService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationGrpcService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstancePreparationService;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceLifecycleReadRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalInstanceTopologyPlan;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingReceipt;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingRepository;
import net.firedevops.firemud.worldmanagement.tenant.WorldCompleteLaunchBindingService;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceRequest;
import net.firedevops.firemud.worldmanagement.v1.PrepareCanonicalWorldInstanceResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Component-only adapter tests; mocked producer and storage results are not PostgreSQL proof. */
class WorldCanonicalInstancePreparationGrpcServiceTest {
  private static final String NAMESPACE = "test";
  private static final UUID TENANT_ID = uuid("22222222-2222-4222-8222-222222222222");
  private static final UUID INSTANCE_ID = uuid("33333333-3333-4333-8333-333333333333");
  private static final UUID PLAYABLE_NAMESPACE_ID = uuid("44444444-4444-4444-8444-444444444444");
  private static final UUID VERSION_ID = uuid("55555555-5555-4555-8555-555555555555");
  private static final UUID READ_ID = uuid("11111111-1111-4111-8111-111111111111");
  private static final String CONTROL_REQUEST_ID = "control-request";
  private static final String DESCRIPTOR_ID = "launch-descriptor";
  private static final String REQUEST_DIGEST = "sha256:" + "a".repeat(64);
  private static final String RESULT_DIGEST = "sha256:" + "b".repeat(64);
  private static final String RELEASE_DIGEST = "sha256:" + "c".repeat(64);

  @AfterEach
  void clearAmbientState() {
    SessionContext.clear();
    TransactionSynchronizationManager.clear();
  }

  @Test
  void rejectsWrongServiceNamespaceAndMissingPeerBeforeAnyComponentInteraction() {
    Harness harness = new Harness("STARTING");
    var request = request(harness.selector);

    assertThat(call(harness.service, request, peer("account-service", NAMESPACE)).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(harness.service, request, peer("game-session-service", "other")).error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(call(harness.service, request, null).error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(
        harness.launchBinding,
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void rejectsOuterWorkloadNamespaceAndEndUserContextBeforeOwnerCalls() {
    Harness harness = new Harness("STARTING");
    var wrongNamespace =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            READ_ID,
            "other",
            TENANT_ID,
            "starter-world",
            INSTANCE_ID,
            CONTROL_REQUEST_ID,
            DESCRIPTOR_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST,
            RELEASE_DIGEST);
    assertThat(
            call(harness.service, request(wrongNamespace), peer("game-session-service", NAMESPACE))
                .error)
        .isEqualTo(Status.Code.PERMISSION_DENIED);

    SessionContext.setContext("66666666-6666-4666-8666-666666666666", List.of(), Map.of());
    Collector endUser;
    try {
      endUser =
          call(
              harness.service,
              PrepareCanonicalWorldInstanceRequest.getDefaultInstance(),
              peer("game-session-service", NAMESPACE));
    } finally {
      SessionContext.clear();
    }
    assertThat(endUser.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verifyNoInteractions(
        harness.launchBinding,
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void rejectsAmbientTransactionBeforeBindingAssemblyOrStorage() {
    Harness harness = new Harness("STARTING");
    TransactionSynchronizationManager.setActualTransactionActive(true);

    Collector result =
        call(harness.service, request(harness.selector), peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(
        harness.launchBinding,
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void invalidClosedSelectorFailsBeforeOwnerCalls() {
    Harness harness = new Harness("STARTING");

    Collector result =
        call(
            harness.service,
            PrepareCanonicalWorldInstanceRequest.getDefaultInstance(),
            peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.INVALID_ARGUMENT);
    verifyNoInteractions(
        harness.launchBinding,
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void unknownCommittedLaunchBindingFailsClosedBeforeAssembly() {
    Harness harness = new Harness("STARTING");
    when(harness.launchBinding.bindCommittedLaunch(harness.selector))
        .thenThrow(
            new WorldCompleteLaunchBindingRepository.InvalidBindingEvidenceException(
                "private missing binding detail"));

    Collector result =
        call(harness.service, request(harness.selector), peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    assertThat(result.description).doesNotContain("private missing binding detail");
    verify(harness.launchBinding).bindCommittedLaunch(harness.selector);
    verifyNoInteractions(
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void unavailableOwnerRpcIsSanitizedAndMappedToUnavailable() {
    Harness harness = new Harness("STARTING");
    when(harness.launchBinding.bindCommittedLaunch(harness.selector))
        .thenThrow(
            Status.UNAVAILABLE.withDescription("private remote detail").asRuntimeException());

    Collector result =
        call(harness.service, request(harness.selector), peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(result.description).doesNotContain("private remote detail");
    verifyNoInteractions(
        harness.assembly,
        harness.preparation,
        harness.preparationRepository,
        harness.lifecycleRepository);
  }

  @Test
  void changedAssembledTupleFailsBeforePreparation() {
    Harness harness = new Harness("STARTING");
    when(harness.innerRequest.expectedDescriptorResultDigest())
        .thenReturn("sha256:" + "d".repeat(64));

    Collector result =
        call(harness.service, request(harness.selector), peer("game-session-service", NAMESPACE));

    assertThat(result.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verify(harness.launchBinding).bindCommittedLaunch(harness.selector);
    verify(harness.assembly)
        .assemble(
            new WorldCanonicalInstancePreparationAssemblyService.Selector(
                TENANT_ID, INSTANCE_ID, CONTROL_REQUEST_ID));
    verifyNoInteractions(
        harness.preparation, harness.preparationRepository, harness.lifecycleRepository);
  }

  @Test
  void freshNonStartingSessionIsDeniedButAnExactHistoricalRetryStillCallsPrepare() {
    Harness fresh = new Harness("RUNNING");
    when(fresh.preparationRepository.readOwnerPreparation(fresh.input))
        .thenReturn(Optional.empty());
    Collector freshResult =
        call(fresh.service, request(fresh.selector), peer("game-session-service", NAMESPACE));
    assertThat(freshResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verifyNoInteractions(fresh.preparation, fresh.lifecycleRepository);

    Harness retry = new Harness("RUNNING");
    when(retry.preparationRepository.readOwnerPreparation(retry.input))
        .thenReturn(Optional.of(retry.prepared));
    when(retry.preparation.prepare(retry.input))
        .thenThrow(
            new WorldCanonicalInstancePreparationService.PreparationDeniedException(
                "held proof denied"));

    Collector retryResult =
        call(retry.service, request(retry.selector), peer("game-session-service", NAMESPACE));

    assertThat(retryResult.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(retry.preparation).prepare(retry.input);
    verifyNoInteractions(retry.lifecycleRepository);
  }

  @Test
  void productionDefaultVerifierDeniesWithoutLifecycleSuccessAndNullPrepareResultIsRejected() {
    Harness defaultDenied = new Harness("STARTING");
    var productionDefault =
        new WorldCanonicalInstancePreparationService(defaultDenied.preparationRepository);
    defaultDenied.replacePreparationService(productionDefault);

    Collector denied =
        call(
            defaultDenied.service,
            request(defaultDenied.selector),
            peer("game-session-service", NAMESPACE));

    assertThat(denied.error).isEqualTo(Status.Code.PERMISSION_DENIED);
    verify(defaultDenied.preparationRepository).readOwnerPreparation(defaultDenied.input);
    verifyNoInteractions(defaultDenied.lifecycleRepository);

    Harness noResult = new Harness("STARTING");
    when(noResult.preparation.prepare(noResult.input)).thenReturn(null);

    Collector absent =
        call(noResult.service, request(noResult.selector), peer("game-session-service", NAMESPACE));

    assertThat(absent.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verify(noResult.preparation).prepare(noResult.input);
    verifyNoInteractions(noResult.lifecycleRepository);
  }

  @Test
  void bindsBeforeAssemblyThenRequiresExactMaterializationAndIndependentLifecycleReadback() {
    Harness harness = new Harness("STARTING");
    var responseLifecycleBytes =
        "canonical lifecycle bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    when(harness.lifecycle.canonicalBytes()).thenReturn(responseLifecycleBytes);

    Collector result =
        call(harness.service, request(harness.selector), peer("game-session-service", NAMESPACE));

    assertThat(result.error).isNull();
    assertThat(result.completed).isTrue();
    assertThat(result.value).isNotNull();
    assertThat(result.value.hasRequest()).isTrue();
    assertThat(result.value.getRequest()).isEqualTo(request(harness.selector));
    assertThat(result.value.hasLifecycle()).isTrue();
    assertThat(result.value.getLifecycle().getCanonicalResponseBytes().toByteArray())
        .containsExactly(responseLifecycleBytes);
    assertThat(harness.lifecycleRequest.readRequestId())
        .isEqualTo(harness.selector.readRequestId())
        .isNotEqualTo(harness.innerRequest.readRequestId());
    var ordered = inOrder(harness.launchBinding, harness.assembly);
    ordered.verify(harness.launchBinding).bindCommittedLaunch(harness.selector);
    ordered
        .verify(harness.assembly)
        .assemble(
            new WorldCanonicalInstancePreparationAssemblyService.Selector(
                TENANT_ID, INSTANCE_ID, CONTROL_REQUEST_ID));
    verify(harness.preparationRepository).readOwnerPreparation(harness.input);
    verify(harness.preparation).prepare(harness.input);
    verify(harness.lifecycleRepository).read(harness.lifecycleRequest);
  }

  @Test
  void missingOrInconsistentIndependentLifecycleEvidenceIsRejected() {
    Harness missing = new Harness("STARTING");
    when(missing.lifecycleRepository.read(missing.lifecycleRequest)).thenReturn(Optional.empty());
    Collector missingResult =
        call(missing.service, request(missing.selector), peer("game-session-service", NAMESPACE));
    assertThat(missingResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);

    Harness inconsistent = new Harness("STARTING");
    when(inconsistent.lifecycle.captureId()).thenReturn(UUID.randomUUID());
    Collector inconsistentResult =
        call(
            inconsistent.service,
            request(inconsistent.selector),
            peer("game-session-service", NAMESPACE));
    assertThat(inconsistentResult.error).isEqualTo(Status.Code.FAILED_PRECONDITION);
    verify(missing.preparation).prepare(missing.input);
    verify(inconsistent.preparation).prepare(inconsistent.input);
  }

  private static PrepareCanonicalWorldInstanceRequest request(
      CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector) {
    return CanonicalWorldInstancePreparationGrpcCodec.toRequest(selector);
  }

  private static Collector call(
      WorldCanonicalInstancePreparationGrpcService service,
      PrepareCanonicalWorldInstanceRequest request,
      GrpcPeerIdentity peer) {
    Collector response = new Collector();
    Context context =
        peer == null ? Context.ROOT : Context.ROOT.withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      service.prepareCanonicalWorldInstance(request, response);
    } finally {
      context.detach(previous);
    }
    return response;
  }

  private static GrpcPeerIdentity peer(String service, String namespace) {
    return new GrpcPeerIdentity(
        "spiffe://firemud/ns/" + namespace + "/sa/" + service, namespace, service);
  }

  private static UUID uuid(String value) {
    return UUID.fromString(value);
  }

  private static final class Harness {
    private final CanonicalGameInstanceLaunchAssociationReadEvidence.Request selector =
        new CanonicalGameInstanceLaunchAssociationReadEvidence.Request(
            READ_ID,
            NAMESPACE,
            TENANT_ID,
            "starter-world",
            INSTANCE_ID,
            CONTROL_REQUEST_ID,
            DESCRIPTOR_ID,
            REQUEST_DIGEST,
            RESULT_DIGEST,
            RELEASE_DIGEST);
    private final WorldCompleteLaunchBindingService launchBinding =
        mock(WorldCompleteLaunchBindingService.class);
    private final WorldCanonicalInstancePreparationAssemblyService assembly =
        mock(WorldCanonicalInstancePreparationAssemblyService.class);
    private WorldCanonicalInstancePreparationService preparation =
        mock(WorldCanonicalInstancePreparationService.class);
    private final WorldCanonicalInstancePreparationRepository preparationRepository =
        mock(WorldCanonicalInstancePreparationRepository.class);
    private final WorldCanonicalInstanceLifecycleReadRepository lifecycleRepository =
        mock(WorldCanonicalInstanceLifecycleReadRepository.class);
    private final GameSessionReadRequest innerRequest = mock(GameSessionReadRequest.class);
    private final GameSessionReadEvidence gameSession = mock(GameSessionReadEvidence.class);
    private final AuthoredWorldLaunchDescriptorEvidence descriptor =
        mock(AuthoredWorldLaunchDescriptorEvidence.class);
    private final AuthoredWorldReleaseAttestationEvidence release =
        mock(AuthoredWorldReleaseAttestationEvidence.class);
    private final CompleteLaunchBindingEvidence pair = mock(CompleteLaunchBindingEvidence.class);
    private final WorldCompleteLaunchBindingReceipt binding =
        mock(WorldCompleteLaunchBindingReceipt.class);
    private final WorldAuthoredVersionIdentityReceipt versionIdentity =
        mock(WorldAuthoredVersionIdentityReceipt.class);
    private final WorldCanonicalInstanceAssociation.CanonicalIdentity identity =
        mock(WorldCanonicalInstanceAssociation.CanonicalIdentity.class);
    private final WorldCanonicalInstanceTopologyPlan topologyPlan =
        mock(WorldCanonicalInstanceTopologyPlan.class);
    private final WorldCanonicalFrozenTopology.Request frozenSource =
        mock(WorldCanonicalFrozenTopology.Request.class);
    private final Input input = mock(Input.class);
    private final Result prepared = mock(Result.class);
    private final WorldCanonicalInstanceAssociation association =
        mock(WorldCanonicalInstanceAssociation.class);
    private final RoomTemplateRef startLocation =
        new RoomTemplateRef(TENANT_ID, VERSION_ID, uuid("77777777-7777-4777-8777-777777777777"));
    private final UUID captureId = uuid("88888888-8888-4888-8888-888888888888");
    private final String graphSha256 = "e".repeat(64);
    private final String inputDigest = "sha256:" + "f".repeat(64);
    private final Request lifecycleRequest;
    private final WorldCanonicalInstanceLifecycleEvidence lifecycle =
        mock(WorldCanonicalInstanceLifecycleEvidence.class);
    private WorldCanonicalInstancePreparationGrpcService service;

    private Harness(String currentStatus) {
      UUID internalReadId = uuid("99999999-9999-4999-8999-999999999999");
      when(innerRequest.readRequestId()).thenReturn(internalReadId);
      when(innerRequest.targetNamespace()).thenReturn(NAMESPACE);
      when(innerRequest.canonicalTenantId()).thenReturn(TENANT_ID);
      when(innerRequest.worldSlug()).thenReturn("starter-world");
      when(innerRequest.canonicalGameInstanceId()).thenReturn(INSTANCE_ID);
      when(innerRequest.controlPlaneRequestId()).thenReturn(CONTROL_REQUEST_ID);
      when(innerRequest.launchDescriptorId()).thenReturn(DESCRIPTOR_ID);
      when(innerRequest.expectedDescriptorRequestDigest()).thenReturn(REQUEST_DIGEST);
      when(innerRequest.expectedDescriptorResultDigest()).thenReturn(RESULT_DIGEST);
      when(innerRequest.expectedReleaseAttestationEvidenceDigest()).thenReturn(RELEASE_DIGEST);

      when(descriptor.targetNamespace()).thenReturn(NAMESPACE);
      when(descriptor.canonicalTenantId()).thenReturn(TENANT_ID);
      when(descriptor.worldSlug()).thenReturn("starter-world");
      when(descriptor.controlPlaneRequestId()).thenReturn(CONTROL_REQUEST_ID);
      when(descriptor.launchDescriptorId()).thenReturn(DESCRIPTOR_ID);
      when(descriptor.requestDigest()).thenReturn(REQUEST_DIGEST);
      when(descriptor.resultDigest()).thenReturn(RESULT_DIGEST);
      when(release.targetNamespace()).thenReturn(NAMESPACE);
      when(release.descriptorResultDigest()).thenReturn(RESULT_DIGEST);
      when(release.canonicalTenantId()).thenReturn(TENANT_ID);
      when(release.canonicalVersionId()).thenReturn(VERSION_ID);
      when(release.worldSlug()).thenReturn("starter-world");
      when(release.launchDescriptorId()).thenReturn(DESCRIPTOR_ID);
      when(release.evidenceDigest()).thenReturn(RELEASE_DIGEST);
      when(pair.descriptor()).thenReturn(descriptor);
      when(pair.releaseAttestation()).thenReturn(release);

      when(gameSession.readRequestId()).thenReturn(internalReadId);
      when(gameSession.targetNamespace()).thenReturn(NAMESPACE);
      when(gameSession.canonicalTenantId()).thenReturn(TENANT_ID);
      when(gameSession.worldSlug()).thenReturn("starter-world");
      when(gameSession.canonicalGameInstanceId()).thenReturn(INSTANCE_ID);
      when(gameSession.controlPlaneRequestId()).thenReturn(CONTROL_REQUEST_ID);
      when(gameSession.launchDescriptorId()).thenReturn(DESCRIPTOR_ID);
      when(gameSession.descriptorRequestDigest()).thenReturn(REQUEST_DIGEST);
      when(gameSession.descriptorResultDigest()).thenReturn(RESULT_DIGEST);
      when(gameSession.releaseAttestationEvidenceDigest()).thenReturn(RELEASE_DIGEST);
      when(gameSession.playableStateNamespaceId()).thenReturn(PLAYABLE_NAMESPACE_ID);
      when(gameSession.playableStateScope()).thenReturn("SHARED");
      when(gameSession.publicProduction()).thenReturn(true);
      when(gameSession.currentGameSessionStatus()).thenReturn(currentStatus);
      when(gameSession.descriptor()).thenReturn(descriptor);
      when(gameSession.releaseAttestation()).thenReturn(release);
      when(gameSession.canonicalIdentity()).thenReturn(identity);

      when(binding.targetNamespace()).thenReturn(NAMESPACE);
      when(binding.canonicalTenantId()).thenReturn(TENANT_ID);
      when(binding.worldSlug()).thenReturn("starter-world");
      when(binding.controlPlaneRequestId()).thenReturn(CONTROL_REQUEST_ID);
      when(binding.evidence()).thenReturn(pair);
      when(binding.descriptor()).thenReturn(descriptor);
      when(input.gameSessionReadRequest()).thenReturn(innerRequest);
      when(input.gameSessionReadEvidence()).thenReturn(gameSession);
      when(input.completeLaunchBinding()).thenReturn(binding);
      when(input.versionIdentity()).thenReturn(versionIdentity);
      when(input.topologyPlan()).thenReturn(topologyPlan);
      when(input.captureId()).thenReturn(captureId);
      // The production default verifier checks generation intent before denying due to missing
      // authenticated held producer authority. This synthetic source is not producer or PG proof.
      when(topologyPlan.sourceBinding()).thenReturn(frozenSource);
      when(topologyPlan.generationRules()).thenReturn(List.of());
      when(topologyPlan.spawnBindings()).thenReturn(List.of());
      when(launchBinding.bindCommittedLaunch(selector)).thenReturn(binding);
      when(assembly.assemble(
              new WorldCanonicalInstancePreparationAssemblyService.Selector(
                  TENANT_ID, INSTANCE_ID, CONTROL_REQUEST_ID)))
          .thenReturn(input);

      when(prepared.association()).thenReturn(association);
      when(prepared.captureId()).thenReturn(captureId);
      when(prepared.graphSha256()).thenReturn(graphSha256);
      when(prepared.inputDigest()).thenReturn(inputDigest);
      when(prepared.startLocation()).thenReturn(startLocation);
      when(prepared.runtimeRoomInstanceId()).thenReturn(9L);
      when(prepared.storageStatus()).thenReturn("MATERIALIZED_UNVERIFIED");
      when(association.identity()).thenReturn(identity);
      when(association.completeLaunchBinding()).thenReturn(binding);
      when(association.versionIdentity()).thenReturn(versionIdentity);
      when(preparationRepository.readOwnerPreparation(input)).thenReturn(Optional.empty());
      when(preparation.prepare(input)).thenReturn(prepared);

      lifecycleRequest =
          new Request(
              WorldCanonicalInstanceLifecycleEvidence.Request.SCHEMA_VERSION,
              READ_ID,
              NAMESPACE,
              TENANT_ID,
              "starter-world",
              INSTANCE_ID,
              PLAYABLE_NAMESPACE_ID,
              "SHARED",
              true,
              CONTROL_REQUEST_ID,
              VERSION_ID,
              REQUEST_DIGEST,
              RESULT_DIGEST,
              RELEASE_DIGEST);
      when(lifecycle.request()).thenReturn(lifecycleRequest);
      when(lifecycle.launchBinding()).thenReturn(pair);
      when(lifecycle.captureId()).thenReturn(captureId);
      when(lifecycle.graphSha256()).thenReturn(graphSha256);
      when(lifecycle.preparationInputDigest()).thenReturn(inputDigest);
      when(lifecycle.startLocation()).thenReturn(startLocation);
      when(lifecycle.runtimeRoomInstanceId()).thenReturn(9L);
      when(lifecycleRepository.read(lifecycleRequest)).thenReturn(Optional.of(lifecycle));

      service = createService();
    }

    private void replacePreparationService(WorldCanonicalInstancePreparationService replacement) {
      preparation = replacement;
      service = createService();
    }

    private WorldCanonicalInstancePreparationGrpcService createService() {
      return new WorldCanonicalInstancePreparationGrpcService(
          launchBinding,
          assembly,
          preparation,
          preparationRepository,
          lifecycleRepository,
          NAMESPACE);
    }
  }

  private static final class Collector
      implements StreamObserver<PrepareCanonicalWorldInstanceResponse> {
    private PrepareCanonicalWorldInstanceResponse value;
    private Status.Code error;
    private String description;
    private boolean completed;

    @Override
    public void onNext(PrepareCanonicalWorldInstanceResponse response) {
      value = response;
    }

    @Override
    public void onError(Throwable failure) {
      assertThat(value).isNull();
      assertThat(completed).isFalse();
      error = Status.fromThrowable(failure).getCode();
      description = Status.fromThrowable(failure).getDescription();
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
