package net.firedevops.firemud.gamelogic.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameLogicGrpcServiceTest {
  private GameLogicDraftDesignDigestService mockDigestService() {
    return Mockito.mock(GameLogicDraftDesignDigestService.class);
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
  void getDraftDesignDigestReturnsVersionScopedDigest() {
    PingService pingService = new PingServiceImpl();
    var dispatcher = new EventDispatcher();
    var processor = new SimpleCommandProcessor(dispatcher, new NoOpScriptingHook());
    var commandService = new CommandServiceImpl(new DefaultCommandParser(), processor);
    LookAggregationService lookAggregationService = Mockito.mock(LookAggregationService.class);
    CommunicationAggregationService communicationAggregationService =
        Mockito.mock(CommunicationAggregationService.class);
    MoveAggregationService moveAggregationService = Mockito.mock(MoveAggregationService.class);
    GameLogicDraftDesignDigestService digestService = mockDigestService();
    Mockito.when(digestService.getDraftDesignDigest("1", "7"))
        .thenReturn(
            new GameLogicDraftDesignDigestService.GameLogicDraftDesignDigest(
                "1", "7", "version:7", "digest-logic", 1));
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

    AtomicReference<GetDraftDesignDigestResponse> ref = new AtomicReference<>();
    service.getDraftDesignDigest(
        GetDraftDesignDigestRequest.newBuilder().setTenantId("1").setVersionId("7").build(),
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
        });

    assertEquals("7", ref.get().getScopeValue());
    assertEquals("version:7", ref.get().getAppliedCommitId());
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
