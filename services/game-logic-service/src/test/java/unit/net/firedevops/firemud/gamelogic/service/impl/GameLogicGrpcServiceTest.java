package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeOperation;
import net.firedevops.firemud.common.gamelogic.GameLogicGameplayRuleIntakeTerminal;
import net.firedevops.firemud.common.gamelogic.GameLogicIntakeAuthorizationBinding;
import net.firedevops.firemud.common.gamelogic.GameLogicPublicationSourceReadBinding;
import net.firedevops.firemud.common.gamelogic.GameplayAbilitySchemaProjection;
import net.firedevops.firemud.common.gamelogic.GameplayRuleManifest;
import net.firedevops.firemud.common.gamelogic.GameplayRuleSelectedSource;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.publication.PublicationDigestRequestBinding;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.common.security.PublicationReadGuard;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.v1.ActorConditionState;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionRequest;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.gamelogic.logic.command.DefaultCommandParser;
import net.firedevops.firemud.gamelogic.logic.command.SimpleCommandProcessor;
import net.firedevops.firemud.gamelogic.logic.event.EventDispatcher;
import net.firedevops.firemud.gamelogic.logic.script.NoOpScriptingHook;
import net.firedevops.firemud.gamelogic.logic.service.CommandServiceImpl;
import net.firedevops.firemud.gamelogic.service.CommunicationAggregationService;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.ItemRuntimeService;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.service.MoveAggregationService;
import net.firedevops.firemud.gamelogic.service.PingService;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicGameplayRuleIntakeRepository;
import net.firedevops.firemud.gamelogic.sourceintake.GameLogicPublicationSourceReadService;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandResponse;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameLogicGrpcServiceTest {
  private static final String TEST_NAMESPACE = "test";
  private static final UUID TEST_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final GrpcPeerIdentity WRONG_PEER =
      new GrpcPeerIdentity(
          "spiffe://firemud/ns/test/sa/world-management-service",
          TEST_NAMESPACE,
          "world-management-service");

  private static PublicationReadGuard publicationReadGuard() {
    return new PublicationReadGuard(TEST_NAMESPACE);
  }

  private static void runAsGameDesign(Runnable action) {
    Context.current()
        .withValue(
            GrpcPeerIdentity.CONTEXT_KEY,
            new GrpcPeerIdentity(
                "spiffe://firemud/ns/test/sa/game-design-service",
                TEST_NAMESPACE,
                "game-design-service"))
        .run(action);
  }

  private static GetDraftDesignDigestRequest fullDigestRequest(String tenantId, String versionId) {
    var authorization = digestAuthorization(UUID.fromString(tenantId), Long.parseLong(versionId));
    return fullDigestRequest(publicationBinding(authorization, "request-7"));
  }

  private static GetDraftDesignDigestRequest fullDigestRequest(
      GameLogicPublicationSourceReadBinding binding) {
    PublicationDigestRequestBinding publicationRequest = binding.publicationRequest();
    return GetDraftDesignDigestRequest.newBuilder()
        .setTenantId(publicationRequest.tenantId())
        .setVersionId(publicationRequest.versionId())
        .setPublishRequestId(publicationRequest.publishRequestId())
        .setDerivedWorkflowIdentity(publicationRequest.derivedWorkflowIdentity())
        .setRequestDigest(publicationRequest.requestDigest())
        .setSourceReadBinding(ByteString.copyFrom(binding.canonicalBytes()))
        .setSourceReadBindingDigest(binding.digest())
        .build();
  }

  private static GameLogicPublicationSourceReadBinding publicationBinding(
      GameLogicIntakeAuthorizationBinding authorization, String publishRequestId) {
    var target = authorization.source().binding().target();
    return new GameLogicPublicationSourceReadBinding(
        PublicationDigestRequestBinding.full(
            authorization.tenantId().toString(),
            Long.toString(target.gameDesignVersionRowId()),
            publishRequestId),
        authorization);
  }

  private static GameLogicIntakeAuthorizationBinding digestAuthorization(
      UUID tenantId, long gameDesignVersionRowId) {
    var actor = UUID.randomUUID();
    var target =
        new DraftCommitBinding.TargetProof(
            tenantId,
            UUID.randomUUID(),
            gameDesignVersionRowId,
            "private",
            29,
            "private",
            "NEW_GAME_ROW");
    var commit =
        DraftCommitBinding.create(
            target,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "genesis",
            List.of(
                new DraftCommitBinding.RevisionPayload(
                    "0",
                    UUID.randomUUID(),
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "{}")),
            List.of(
                new DraftCommitBinding.AffectedUnit(
                    DraftCommitBinding.Owner.GAME_DESIGN_CONTROL_PLANE,
                    "GAMEPLAY_RULE_SET",
                    target.canonicalVersionId().toString(),
                    "GAMEPLAY_RULE_SET",
                    "effective",
                    "0")));
    var source =
        new GameplayRuleSelectedSource(
            GameplayRuleManifest.canonical(
                Map.of(
                    "schema", "game-design-gameplay-rule-source-snapshot/v1",
                    "bindingJson", commit.canonicalJson(),
                    "bindingDigest", commit.digest(),
                    "sourceEpoch", "1",
                    "inheritedCommitId", "",
                    "genesisReceiptId", UUID.randomUUID().toString(),
                    "manifestJson", GameplayRuleManifest.explicitEmpty().canonicalJson(),
                    "entries", List.of())));
    var accountSource =
        new DraftAuthorizationFenceBinding.SourceEvidence(
            DraftAuthorizationFenceBinding.SourceKind.ACCOUNT,
            actor.toString(),
            "1",
            "1",
            null,
            null,
            new byte[] {1, 2, 3});
    return new GameLogicIntakeAuthorizationBinding(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        actor,
        source,
        List.of(accountSource));
  }

  private static GameLogicGameplayRuleIntakeTerminal retainedTerminal(
      GameLogicIntakeAuthorizationBinding authorization) {
    return GameLogicGameplayRuleIntakeTerminal.retained(
        new GameLogicGameplayRuleIntakeOperation(TEST_NAMESPACE, authorization),
        authorization.source().canonicalBytes(),
        authorization.source().manifest().canonicalBytes());
  }

  private GameLogicDraftDesignDigestService mockDigestService() {
    return Mockito.mock(GameLogicDraftDesignDigestService.class);
  }

  @AfterEach
  void clearSessionContext() {
    SessionContext.clear();
  }

  private GameplaySessionAttestationService mockAttestationService() {
    return Mockito.mock(GameplaySessionAttestationService.class);
  }

  @Test
  void pingEndpointReturnsPong() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<PingResponse> holder = new AtomicReference<>();
    service.ping(
        PingRequest.newBuilder().build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PingResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("pong", holder.get().getMessage());
  }

  @Test
  void executeCommandReturnsInvalidArgument() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<ExecuteCommandResponse> holder = new AtomicReference<>();
    service.executeCommand(
        ExecuteCommandRequest.newBuilder().setCommand("foo").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(ExecuteCommandResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("Unknown action", holder.get().getResult());
    assertEquals("UNKNOWN_COMMAND", holder.get().getError().getCode());
    assertEquals("Command not recognized", holder.get().getError().getMessage());
  }

  @Test
  void resolveMoveReturnsDestinationRoom() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    MoveResult moveResult =
        MoveResult.newBuilder()
            .setSuccess(true)
            .setDestinationRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId("22")
                    .setGameInstanceId("7")
                    .setRoomInstanceId("R-2045")
                    .build())
            .build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<MoveResult> holder = new AtomicReference<>();
    service.resolveMove(
        MoveRequest.newBuilder().setDirection("NORTH").build(),
        new StreamObserver<>() {
          @Override
          public void onNext(MoveResult value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    Mockito.verify(moveAggregationService).resolve(any());
    assertTrue(holder.get().getSuccess());
    assertEquals("22", holder.get().getDestinationRoomInstance().getTenantId());
    assertEquals("7", holder.get().getDestinationRoomInstance().getGameInstanceId());
    assertEquals("R-2045", holder.get().getDestinationRoomInstance().getRoomInstanceId());
  }

  @Test
  void getDraftDesignDigestMapsUnconfiguredRetainedSourceToErrorResponse() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    Mockito.when(
            digestService.getDraftDesignDigest(any(GameLogicPublicationSourceReadBinding.class)))
        .thenThrow(
            new GameLogicDraftDesignDigestService.UnsupportedDigestScopeException(
                "Game Logic retained publication source reader is not configured"));
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry(),
            publicationReadGuard());

    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    runAsGameDesign(
        () ->
            service.getDraftDesignDigest(
                fullDigestRequest(TEST_TENANT.toString(), "7"),
                new StreamObserver<>() {
                  @Override
                  public void onNext(GetDraftDesignDigestResponse value) {
                    ref.set(value);
                  }

                  @Override
                  public void onError(Throwable t) {
                    fail(t);
                  }

                  @Override
                  public void onCompleted() {}
                }));

    assertTrue(ref.get().hasError());
    assertEquals("UNSUPPORTED_SCOPE", ref.get().getError().getCode());
    assertEquals(
        "Game Logic cannot attest the requested full-version digest scope",
        ref.get().getError().getMessage());
    assertEquals("", ref.get().getAppliedCommitId());
    assertEquals("", ref.get().getContentDigest());
    assertEquals(0, ref.get().getDigestSchemaVersion());
  }

  @Test
  void getDraftDesignDigestEchoesTheExactRetainedSourceAndCanonicalDigests() {
    var authorization = digestAuthorization(TEST_TENANT, 7);
    var binding = publicationBinding(authorization, "request-7");
    var terminal = retainedTerminal(authorization);
    var repository = Mockito.mock(GameLogicGameplayRuleIntakeRepository.class);
    Mockito.when(repository.findTerminal(authorization.operationId()))
        .thenReturn(Optional.of(terminal));
    var digestService =
        new GameLogicDraftDesignDigestServiceImpl(
            new GameLogicPublicationSourceReadService(repository, TEST_NAMESPACE));
    var service = newDigestService(digestService);
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");

    AtomicReference<GetDraftDesignDigestResponse> responseReference = new AtomicReference<>();
    runAsGameDesign(() -> responseReference.set(invokeDigest(service, fullDigestRequest(binding))));
    GetDraftDesignDigestResponse response = responseReference.get();

    assertEquals("", response.getError().getCode());
    assertEquals(TEST_TENANT.toString(), response.getTenantId());
    assertEquals("7", response.getVersionId());
    assertEquals(
        authorization.source().binding().commitId().toString(), response.getAppliedCommitId());
    assertEquals(authorization.source().manifest().digest(), response.getContentDigest());
    assertEquals(1, response.getDigestSchemaVersion());
    assertEquals(
        GameplayAbilitySchemaProjection.digest(authorization.source().manifest()),
        response.getAbilitySchemaDigest());
    assertEquals("RFC8785", response.getCanonicalization());
    assertEquals(binding.digest(), response.getSourceReadBindingDigest());
    assertArrayEquals(binding.canonicalBytes(), response.getSourceReadBinding().toByteArray());
    assertEquals(terminal.digest(), response.getRetainedIntakeTerminalDigest());
    assertArrayEquals(
        terminal.canonicalBytes(), response.getRetainedIntakeTerminal().toByteArray());
    Mockito.verify(repository).findTerminal(authorization.operationId());
  }

  @Test
  void getDraftDesignDigestRejectsMissingBindingAndUnversionedScopeBeforeServiceCall() {
    var digestService = mockDigestService();
    var service = newDigestService(digestService);
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");
    var valid = fullDigestRequest(TEST_TENANT.toString(), "7");
    var missingBinding =
        valid.toBuilder().clearSourceReadBinding().clearSourceReadBindingDigest().build();
    var unversionedScope = valid.toBuilder().clearScope().build();

    runAsGameDesign(
        () -> {
          var missingResponse = invokeDigest(service, missingBinding);
          assertEquals("INVALID_ARGUMENT", missingResponse.getError().getCode());
          assertEquals("", missingResponse.getContentDigest());
          assertEquals(0, missingResponse.getDigestSchemaVersion());
          var scopeResponse = invokeDigest(service, unversionedScope);
          assertEquals("INVALID_ARGUMENT", scopeResponse.getError().getCode());
          assertEquals("", scopeResponse.getContentDigest());
          assertEquals(0, scopeResponse.getDigestSchemaVersion());
        });
    Mockito.verifyNoInteractions(digestService);
  }

  @Test
  void getDraftDesignDigestDeniesSourceBindingThatDiffersFromRetainedOperation() {
    var authorization = digestAuthorization(TEST_TENANT, 7);
    var stored = retainedTerminal(authorization);
    var changedAuthorization =
        new GameLogicIntakeAuthorizationBinding(
            authorization.operationId(),
            UUID.randomUUID(),
            authorization.intakeRequestId(),
            authorization.actorAccountId(),
            authorization.source(),
            authorization.sources());
    var changedBinding = publicationBinding(changedAuthorization, "request-7");
    var repository = Mockito.mock(GameLogicGameplayRuleIntakeRepository.class);
    Mockito.when(repository.findTerminal(authorization.operationId()))
        .thenReturn(Optional.of(stored));
    var service =
        newDigestService(
            new GameLogicDraftDesignDigestServiceImpl(
                new GameLogicPublicationSourceReadService(repository, TEST_NAMESPACE)));
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");

    AtomicReference<GetDraftDesignDigestResponse> responseReference = new AtomicReference<>();
    runAsGameDesign(
        () -> responseReference.set(invokeDigest(service, fullDigestRequest(changedBinding))));
    GetDraftDesignDigestResponse response = responseReference.get();

    assertEquals("ALREADY_EXISTS", response.getError().getCode());
    assertEquals("", response.getContentDigest());
    assertEquals("", response.getAbilitySchemaDigest());
    assertEquals(0, response.getDigestSchemaVersion());
    assertEquals(ByteString.EMPTY, response.getRetainedIntakeTerminal());
    Mockito.verify(repository).findTerminal(authorization.operationId());
  }

  @Test
  void getDraftDesignDigestMapsUnexpectedUnsupportedOperationToInternalError() {
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    Mockito.when(
            digestService.getDraftDesignDigest(any(GameLogicPublicationSourceReadBinding.class)))
        .thenThrow(new UnsupportedOperationException("unexpected implementation failure"));
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");
    GameLogicGrpcService service = newDigestService(digestService);
    AtomicReference<GetDraftDesignDigestResponse> response = new AtomicReference<>();

    runAsGameDesign(
        () -> response.set(invokeDigest(service, fullDigestRequest(TEST_TENANT.toString(), "7"))));

    assertTrue(response.get().hasError());
    assertEquals("INTERNAL", response.get().getError().getCode());
    assertEquals("Internal error", response.get().getError().getMessage());
  }

  @Test
  void getDraftDesignDigestRejectsMissingPeerIdentityForInternalCaller() {
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            new PingServiceImpl(),
            new CommandServiceImpl(
                new DefaultCommandParser(),
                new SimpleCommandProcessor(new EventDispatcher(), new NoOpScriptingHook())),
            Mockito.mock(LookAggregationService.class),
            Mockito.mock(CommunicationAggregationService.class),
            Mockito.mock(MoveAggregationService.class),
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry(),
            publicationReadGuard());
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");
    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    Context.current()
        .withValue(GrpcPeerIdentity.CONTEXT_KEY, null)
        .run(
            () ->
                service.getDraftDesignDigest(
                    fullDigestRequest(TEST_TENANT.toString(), "7"),
                    new StreamObserver<>() {
                      @Override
                      public void onNext(GetDraftDesignDigestResponse value) {
                        ref.set(value);
                      }

                      @Override
                      public void onError(Throwable t) {}

                      @Override
                      public void onCompleted() {}
                    }));
    assertEquals("PERMISSION_DENIED", ref.get().getError().getCode());
    Mockito.verifyNoInteractions(digestService);
  }

  @Test
  void getDraftDesignDigestRejectsWrongPeerAndUserOrAdminJwt() {
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    GameLogicGrpcService service = newDigestService(digestService);
    GetDraftDesignDigestRequest request = fullDigestRequest(TEST_TENANT.toString(), "7");

    SessionContext.setContext(null, List.of(), Map.of(), true, "game-design-service", "instance-1");
    withPeer(
        WRONG_PEER,
        () ->
            assertEquals("PERMISSION_DENIED", invokeDigest(service, request).getError().getCode()));

    withPeer(
        new GrpcPeerIdentity(
            "spiffe://firemud/ns/test/sa/game-design-service",
            TEST_NAMESPACE,
            "game-design-service"),
        () -> {
          SessionContext.setContext("42", List.of(), Map.of(), false, "game-design-service", null);
          assertEquals("PERMISSION_DENIED", invokeDigest(service, request).getError().getCode());
          SessionContext.setContext(
              "42", List.of("platformAdmin"), Map.of(), false, "game-design-service", null);
          assertEquals("PERMISSION_DENIED", invokeDigest(service, request).getError().getCode());
        });
    Mockito.verifyNoInteractions(digestService);
  }

  private GameLogicGrpcService newDigestService(GameLogicDraftDesignDigestService digestService) {
    return newDigestService(digestService, TEST_NAMESPACE);
  }

  private GameLogicGrpcService newDigestService(
      GameLogicDraftDesignDigestService digestService, String workloadNamespace) {
    return new GameLogicGrpcService(
        new PingServiceImpl(),
        new CommandServiceImpl(
            new DefaultCommandParser(),
            new SimpleCommandProcessor(new EventDispatcher(), new NoOpScriptingHook())),
        Mockito.mock(LookAggregationService.class),
        Mockito.mock(CommunicationAggregationService.class),
        Mockito.mock(MoveAggregationService.class),
        Mockito.mock(ItemRuntimeService.class),
        digestService,
        mockAttestationService(),
        new SimpleMeterRegistry(),
        workloadNamespace);
  }

  @Test
  void getDraftDesignDigestFailsClosedWhenWorkloadNamespaceIsMissingOrInvalid() {
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    SessionContext.setContext(
        null, List.of(), Map.of(), true, "game-design-service", "test-instance");
    for (String workloadNamespace : new String[] {null, " ", "not a namespace"}) {
      GameLogicGrpcService service = newDigestService(digestService, workloadNamespace);

      runAsGameDesign(
          () ->
              assertEquals(
                  "PERMISSION_DENIED",
                  invokeDigest(service, fullDigestRequest(TEST_TENANT.toString(), "7"))
                      .getError()
                      .getCode()));
    }
    Mockito.verifyNoInteractions(digestService);
  }

  private GetDraftDesignDigestResponse invokeDigest(
      GameLogicGrpcService service, GetDraftDesignDigestRequest request) {
    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    service.getDraftDesignDigest(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(GetDraftDesignDigestResponse value) {
            ref.set(value);
          }

          @Override
          public void onError(Throwable t) {
            failure.set(t);
          }

          @Override
          public void onCompleted() {}
        });
    if (failure.get() != null) {
      throw new AssertionError("getDraftDesignDigest completed with an error", failure.get());
    }
    GetDraftDesignDigestResponse response = ref.get();
    if (response == null) {
      throw new AssertionError("getDraftDesignDigest completed without a response");
    }
    return response;
  }

  private static void withPeer(GrpcPeerIdentity peer, Runnable action) {
    Context context = Context.current().withValue(GrpcPeerIdentity.CONTEXT_KEY, peer);
    Context previous = context.attach();
    try {
      action.run();
    } finally {
      context.detach(previous);
    }
  }

  @Test
  void resolveLookReturnsErrorDetailInsteadOfTransportError() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    MoveResult moveResult = MoveResult.newBuilder().setSuccess(true).build();
    Mockito.when(moveAggregationService.resolve(any())).thenReturn(moveResult);
    Mockito.when(lookAggregationService.resolve(any()))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("down")));
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<LookResult> holder = new AtomicReference<>();
    service.resolveLook(
        LookRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("1")
            .setCharacterId("911")
            .setPreferredLocale("")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(LookResult value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertTrue(holder.get().hasError());
    assertEquals("WORLD_UNAVAILABLE", holder.get().getError().getCode());
    assertTrue(holder.get().getError().getMessage().contains("down"));
  }

  @Test
  void resolveLookPreservesInvalidArgumentForMalformedRuntimeRoomIds() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    Mockito.when(lookAggregationService.resolve(any()))
        .thenThrow(
            new StatusRuntimeException(
                Status.INVALID_ARGUMENT.withDescription(
                    "room_instance.room_instance_id must be a runtime room id like R-1021")));
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            lookAggregationService,
            communicationAggregationService,
            moveAggregationService,
            Mockito.mock(ItemRuntimeService.class),
            digestService,
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<LookResult> holder = new AtomicReference<>();
    service.resolveLook(
        LookRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("1")
            .setCharacterId("911")
            .setPreferredLocale("")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(LookResult value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertTrue(holder.get().hasError());
    assertEquals("INVALID_ARGUMENT", holder.get().getError().getCode());
    assertTrue(
        holder
            .get()
            .getError()
            .getMessage()
            .contains("room_instance.room_instance_id must be a runtime room id like R-1021"));
  }

  @Test
  void queryInventoryDelegatesToItemRuntimeService() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    ItemRuntimeService itemRuntimeService = Mockito.mock(ItemRuntimeService.class);
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId("22")
            .setCharacterId("911")
            .setSessionAttestation("attestation")
            .build();
    Mockito.when(itemRuntimeService.queryInventory(request))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(InventoryItem.newBuilder().setItemId("7").setItemName("Torch").build())
                .build());
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            Mockito.mock(LookAggregationService.class),
            Mockito.mock(CommunicationAggregationService.class),
            Mockito.mock(MoveAggregationService.class),
            itemRuntimeService,
            mockDigestService(),
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<QueryInventoryResponse> holder = new AtomicReference<>();
    service.queryInventory(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(QueryInventoryResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("Torch", holder.get().getItems(0).getItemName());
  }

  @Test
  void applyActorConditionDelegatesToItemRuntimeService() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    ItemRuntimeService itemRuntimeService = Mockito.mock(ItemRuntimeService.class);
    ApplyActorConditionRequest request =
        ApplyActorConditionRequest.newBuilder()
            .setTenantId("22")
            .setCharacterId("911")
            .setConditionKey("blocking")
            .setSessionAttestation("attestation")
            .build();
    Mockito.when(itemRuntimeService.applyActorCondition(request))
        .thenReturn(
            ApplyActorConditionResponse.newBuilder()
                .setActiveCondition(
                    ActorConditionState.newBuilder().setConditionKey("blocking").build())
                .build());
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            Mockito.mock(LookAggregationService.class),
            Mockito.mock(CommunicationAggregationService.class),
            Mockito.mock(MoveAggregationService.class),
            itemRuntimeService,
            mockDigestService(),
            mockAttestationService(),
            new SimpleMeterRegistry());

    AtomicReference<ApplyActorConditionResponse> holder = new AtomicReference<>();
    service.applyActorCondition(
        request,
        new StreamObserver<>() {
          @Override
          public void onNext(ApplyActorConditionResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("blocking", holder.get().getActiveCondition().getConditionKey());
  }

  @Test
  void pickupVisibleRoomItemReturnsAppErrorWhenRoutingBundleAttestationIsInvalid() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    ItemRuntimeService itemRuntimeService = Mockito.mock(ItemRuntimeService.class);
    GameplaySessionAttestationService attestationService = mockAttestationService();
    Mockito.doThrow(
            new GameplaySessionAttestationException(
                "SESSION_ATTESTATION_INVALID",
                "Gameplay session attestation is missing pointerVersion"))
        .when(attestationService)
        .requireAdmittedRoutingBundle(Mockito.any());
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            Mockito.mock(LookAggregationService.class),
            Mockito.mock(CommunicationAggregationService.class),
            Mockito.mock(MoveAggregationService.class),
            itemRuntimeService,
            mockDigestService(),
            attestationService,
            new SimpleMeterRegistry());

    AtomicReference<PickupItemFromRoomResponse> holder = new AtomicReference<>();
    service.pickupVisibleRoomItem(
        PickupVisibleRoomItemRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("1")
            .setAccountId("7")
            .setCharacterId("911")
            .setGameInstanceId("5")
            .setRoomInstanceId("R-1")
            .setItemReference("torch")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(PickupItemFromRoomResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("SESSION_ATTESTATION_INVALID", holder.get().getError().getCode());
    Mockito.verify(itemRuntimeService, Mockito.never()).pickupVisibleRoomItem(Mockito.any());
  }

  @Test
  void dropCarriedItemReturnsAppErrorWhenRoutingBundleAttestationIsInvalid() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    ItemRuntimeService itemRuntimeService = Mockito.mock(ItemRuntimeService.class);
    GameplaySessionAttestationService attestationService = mockAttestationService();
    Mockito.doThrow(
            new GameplaySessionAttestationException(
                "SESSION_ATTESTATION_INVALID",
                "Gameplay session attestation is missing pointerVersion"))
        .when(attestationService)
        .requireAdmittedRoutingBundle(Mockito.any());
    GameLogicGrpcService service =
        new GameLogicGrpcService(
            pingService,
            commandService,
            Mockito.mock(LookAggregationService.class),
            Mockito.mock(CommunicationAggregationService.class),
            Mockito.mock(MoveAggregationService.class),
            itemRuntimeService,
            mockDigestService(),
            attestationService,
            new SimpleMeterRegistry());

    AtomicReference<DropItemToRoomResponse> holder = new AtomicReference<>();
    service.dropCarriedItem(
        DropCarriedItemRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("1")
            .setAccountId("7")
            .setCharacterId("911")
            .setGameInstanceId("5")
            .setRoomInstanceId("R-1")
            .setItemReference("torch")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build(),
        new StreamObserver<>() {
          @Override
          public void onNext(DropItemToRoomResponse value) {
            holder.set(value);
          }

          @Override
          public void onError(Throwable t) {
            fail(t);
          }

          @Override
          public void onCompleted() {}
        });

    assertEquals("SESSION_ATTESTATION_INVALID", holder.get().getError().getCode());
    Mockito.verify(itemRuntimeService, Mockito.never()).dropCarriedItem(Mockito.any());
  }
}
