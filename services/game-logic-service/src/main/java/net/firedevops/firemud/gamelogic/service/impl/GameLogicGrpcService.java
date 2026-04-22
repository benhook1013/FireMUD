package net.firedevops.firemud.gamelogic.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import net.firedevops.firemud.common.grpc.GrpcAppErrors;
import net.firedevops.firemud.common.security.GameplaySessionAttestationException;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamelogic.logic.dto.CommandResult;
import net.firedevops.firemud.gamelogic.logic.service.CommandService;
import net.firedevops.firemud.gamelogic.service.CommunicationAggregationService;
import net.firedevops.firemud.gamelogic.service.GameLogicDraftDesignDigestService;
import net.firedevops.firemud.gamelogic.service.ItemRuntimeService;
import net.firedevops.firemud.gamelogic.service.LookAggregationService;
import net.firedevops.firemud.gamelogic.service.MoveAggregationService;
import net.firedevops.firemud.gamelogic.service.PingService;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandRequest;
import net.firedevops.firemud.gamelogic.v1.ExecuteCommandResponse;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestRequest;
import net.firedevops.firemud.gamelogic.v1.GetDraftDesignDigestResponse;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.service.GrpcService;

/** gRPC endpoints for the Game Logic Service. */
@GrpcService
public class GameLogicGrpcService extends GameLogicServiceGrpc.GameLogicServiceImplBase {
  private static final Logger logger = LoggerFactory.getLogger(GameLogicGrpcService.class);
  private final PingService pingService;
  private final CommandService commandService;
  private final LookAggregationService lookAggregationService;
  private final CommunicationAggregationService communicationAggregationService;
  private final MoveAggregationService moveAggregationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring services are framework-managed singletons and only stored")
  private final ItemRuntimeService itemRuntimeService;

  private final GameLogicDraftDesignDigestService gameLogicDraftDesignDigestService;
  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "MeterRegistry is thread-safe and only stored")
  private final MeterRegistry meterRegistry;

  public GameLogicGrpcService(
      PingService pingService,
      CommandService commandService,
      LookAggregationService lookAggregationService,
      CommunicationAggregationService communicationAggregationService,
      MoveAggregationService moveAggregationService,
      ItemRuntimeService itemRuntimeService,
      GameLogicDraftDesignDigestService gameLogicDraftDesignDigestService,
      GameplaySessionAttestationService gameplaySessionAttestationService,
      MeterRegistry meterRegistry) {
    this.pingService = pingService;
    this.commandService = commandService;
    this.lookAggregationService = lookAggregationService;
    this.communicationAggregationService = communicationAggregationService;
    this.moveAggregationService = moveAggregationService;
    this.itemRuntimeService = itemRuntimeService;
    this.gameLogicDraftDesignDigestService = gameLogicDraftDesignDigestService;
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Timed(value = "gamelogicGrpc.getDraftDesignDigest")
  public void getDraftDesignDigest(
      GetDraftDesignDigestRequest request,
      StreamObserver<GetDraftDesignDigestResponse> responseObserver) {
    try {
      if (request.getScopeCase() != GetDraftDesignDigestRequest.ScopeCase.VERSION_ID) {
        responseObserver.onNext(
            GetDraftDesignDigestResponse.newBuilder()
                .setError(
                    GrpcAppErrors.error(
                        meterRegistry,
                        logger,
                        "GetDraftDesignDigest",
                        "UNSUPPORTED_SCOPE",
                        "game logic supports version_id scope only"))
                .build());
        responseObserver.onCompleted();
        return;
      }
      var digest =
          gameLogicDraftDesignDigestService.getDraftDesignDigest(
              request.getTenantId(), request.getVersionId());
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setTenantId(digest.tenantId())
              .setScopeValue(digest.scopeValue())
              .setAppliedCommitId(digest.appliedCommitId())
              .setContentDigest(digest.contentDigest())
              .setDigestSchemaVersion(digest.digestSchemaVersion())
              .build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry,
                      logger,
                      "GetDraftDesignDigest",
                      "INVALID_ARGUMENT",
                      ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          GetDraftDesignDigestResponse.newBuilder()
              .setError(GrpcAppErrors.internal(meterRegistry, logger, "GetDraftDesignDigest", ex))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamelogicGrpc.ping")
  public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
    PingResponse response = PingResponse.newBuilder().setMessage(pingService.ping()).build();
    responseObserver.onNext(response);
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.executeCommand")
  public void executeCommand(
      ExecuteCommandRequest request, StreamObserver<ExecuteCommandResponse> responseObserver) {
    CommandResult result = commandService.handleCommand(request.getCommand());
    ExecuteCommandResponse.Builder builder =
        ExecuteCommandResponse.newBuilder().setResult(result.result());
    if (result.error() != null) {
      builder.setError(
          GrpcAppErrors.error(meterRegistry, result.error().code(), result.error().message()));
    }
    responseObserver.onNext(builder.build());
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.resolveLook")
  public void resolveLook(LookRequest request, StreamObserver<LookResult> responseObserver) {
    try {
      gameplaySessionAttestationService.requireGameplaySessionMatch(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getSessionId(),
          null,
          request.getCharacterId(),
          request.getRoomInstance().getGameInstanceId(),
          request.getRoomInstance().getRoomInstanceId());
      LookResult result = lookAggregationService.resolve(request);
      responseObserver.onNext(result);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      responseObserver.onNext(
          LookResult.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ResolveLook", ex.getCode(), ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException ex) {
      responseObserver.onNext(LookResult.newBuilder().setError(mapLookError(ex)).build());
      responseObserver.onCompleted();
    } catch (Exception ex) {
      responseObserver.onNext(
          LookResult.newBuilder()
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ResolveLook", "LOOK_UNAVAILABLE", ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    }
  }

  private ErrorDetail mapLookError(StatusRuntimeException ex) {
    Status.Code code = ex.getStatus().getCode();
    String description = ex.getStatus().getDescription();
    String mappedCode =
        switch (code) {
          case NOT_FOUND -> "ROOM_NOT_FOUND";
          case UNAVAILABLE ->
              description != null && description.contains("EntityManagement")
                  ? "ENTITY_UNAVAILABLE"
                  : "WORLD_UNAVAILABLE";
          case DEADLINE_EXCEEDED -> "WORLD_UNAVAILABLE";
          case PERMISSION_DENIED -> "NOT_AUTHORIZED";
          case FAILED_PRECONDITION -> "READ_FENCE_MISMATCH";
          default -> "LOOK_UNAVAILABLE";
        };
    return GrpcAppErrors.error(meterRegistry, logger, "ResolveLook", mappedCode, description);
  }

  @Override
  @Timed(value = "gamelogicGrpc.sendCommunication")
  public void sendCommunication(
      SendCommunicationRequest request,
      StreamObserver<SendCommunicationResponse> responseObserver) {
    try {
      gameplaySessionAttestationService.requireGameplaySessionMatch(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getSessionId(),
          request.getAccountId(),
          request.getCharacterId(),
          request.getGameInstanceId(),
          request.getRoomInstance().getRoomInstanceId());
      SendCommunicationResponse response = communicationAggregationService.send(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      responseObserver.onNext(
          SendCommunicationResponse.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "SendCommunication", ex.getCode(), ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamelogicGrpc.resolveMove")
  public void resolveMove(MoveRequest request, StreamObserver<MoveResult> responseObserver) {
    try {
      gameplaySessionAttestationService.requireGameplaySessionMatch(
          request.getSessionAttestation(),
          request.getTenantId(),
          request.getSessionId(),
          null,
          request.getCharacterId(),
          request.getRoomInstance().getGameInstanceId(),
          request.getRoomInstance().getRoomInstanceId());
      MoveResult response = moveAggregationService.resolve(request);
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (GameplaySessionAttestationException ex) {
      responseObserver.onNext(
          MoveResult.newBuilder()
              .setSuccess(false)
              .setError(
                  GrpcAppErrors.error(
                      meterRegistry, logger, "ResolveMove", ex.getCode(), ex.getMessage()))
              .build());
      responseObserver.onCompleted();
    }
  }

  @Override
  @Timed(value = "gamelogicGrpc.queryInventory")
  public void queryInventory(
      QueryInventoryRequest request, StreamObserver<QueryInventoryResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.queryInventory(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.listRoomGroundInventory")
  public void listRoomGroundInventory(
      ListRoomGroundInventoryRequest request,
      StreamObserver<ListRoomGroundInventoryResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.listRoomGroundInventory(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.pickupItemFromRoom")
  public void pickupItemFromRoom(
      PickupItemFromRoomRequest request,
      StreamObserver<PickupItemFromRoomResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.pickupItemFromRoom(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.dropItemToRoom")
  public void dropItemToRoom(
      DropItemToRoomRequest request, StreamObserver<DropItemToRoomResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.dropItemToRoom(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.pickupVisibleRoomItem")
  public void pickupVisibleRoomItem(
      PickupVisibleRoomItemRequest request,
      StreamObserver<PickupItemFromRoomResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.pickupVisibleRoomItem(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.dropCarriedItem")
  public void dropCarriedItem(
      DropCarriedItemRequest request, StreamObserver<DropItemToRoomResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.dropCarriedItem(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.listEquipment")
  public void listEquipment(
      ListEquipmentRequest request, StreamObserver<ListEquipmentResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.listEquipment(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.wearEquipment")
  public void wearEquipment(
      WearEquipmentItemRequest request,
      StreamObserver<WearEquipmentItemResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.wearEquipment(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.removeEquipment")
  public void removeEquipment(
      RemoveEquipmentRequest request, StreamObserver<RemoveEquipmentResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.removeEquipment(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.listContainerContents")
  public void listContainerContents(
      ListContainerContentsRequest request,
      StreamObserver<ListContainerContentsResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.listContainerContents(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.putItemIntoContainer")
  public void putItemIntoContainer(
      PutItemIntoContainerRequest request,
      StreamObserver<PutItemIntoContainerResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.putItemIntoContainer(request));
    responseObserver.onCompleted();
  }

  @Override
  @Timed(value = "gamelogicGrpc.takeItemFromContainer")
  public void takeItemFromContainer(
      TakeItemFromContainerRequest request,
      StreamObserver<TakeItemFromContainerResponse> responseObserver) {
    responseObserver.onNext(itemRuntimeService.takeItemFromContainer(request));
    responseObserver.onCompleted();
  }
}
