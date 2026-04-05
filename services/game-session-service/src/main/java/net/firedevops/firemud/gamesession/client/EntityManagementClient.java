package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** gRPC client for the Entity Management Service. */
@Component
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = false)
public final class EntityManagementClient
    extends AbstractBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(EntityManagementClient.class);
  private static final long CALL_DEADLINE_SECONDS = 5L;

  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory) {
    super(endpoints, tlsProps, channelFactory);
  }

  @PostConstruct
  void init() throws SSLException {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getEntityManagementService();
  }

  @Override
  protected String defaultTarget() {
    return "entity-management-service:6565";
  }

  @Override
  protected EntityManagementServiceGrpc.EntityManagementServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip");
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return callStub().ping(PingRequest.newBuilder().build());
  }

  public Optional<Character> findCharacterByName(String tenantId, String name) {
    FindCharacterByNameResponse response =
        callStub()
            .findCharacterByName(
                FindCharacterByNameRequest.newBuilder()
                    .setTenantId(tenantId)
                    .setName(name)
                    .build());
    if (response.hasError() || !response.hasCharacter()) {
      return Optional.empty();
    }
    return Optional.of(response.getCharacter());
  }

  public QueryInventoryResponse queryInventory(String tenantId, String characterId) {
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .build();
    try {
      return callStub().queryInventory(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying inventory query",
            ex);
        try {
          initClient();
          return callStub().queryInventory(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management inventory query after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management inventory query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management inventory query endpoint", ex);
    }
    return QueryInventoryResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("INVENTORY_UNAVAILABLE")
                .setMessage("Inventory service unavailable"))
        .build();
  }

  public ListRoomEntitiesResponse listRoomEntities(
      String tenantId, String gameInstanceId, String roomInstanceId) {
    ListRoomEntitiesRequest request =
        ListRoomEntitiesRequest.newBuilder()
            .setTenantId(tenantId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomInstanceId)
                    .build())
            .build();
    try {
      return callStub().listRoomEntities(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying room entity query",
            ex);
        try {
          initClient();
          return callStub().listRoomEntities(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management room entity query after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management room entity query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management room entity query endpoint", ex);
    }
    return ListRoomEntitiesResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("ROOM_ENTITIES_UNAVAILABLE")
                .setMessage("Room entity service unavailable"))
        .build();
  }

  public PickupItemFromRoomResponse pickupItemFromRoom(
      String tenantId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      String itemId,
      int quantity) {
    PickupItemFromRoomRequest request =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .build();
    try {
      return callStub().pickupItemFromRoom(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying inventory pickup",
            ex);
        try {
          initClient();
          return callStub().pickupItemFromRoom(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management inventory pickup after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management inventory pickup endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management inventory pickup endpoint", ex);
    }
    return PickupItemFromRoomResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("INVENTORY_UNAVAILABLE")
                .setMessage("Inventory service unavailable"))
        .build();
  }

  public DropItemToRoomResponse dropItemToRoom(
      String tenantId,
      String characterId,
      String gameInstanceId,
      String roomInstanceId,
      String itemId,
      int quantity) {
    DropItemToRoomRequest request =
        DropItemToRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .build();
    try {
      return callStub().dropItemToRoom(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying inventory drop",
            ex);
        try {
          initClient();
          return callStub().dropItemToRoom(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management inventory drop after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management inventory drop endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management inventory drop endpoint", ex);
    }
    return DropItemToRoomResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("INVENTORY_UNAVAILABLE")
                .setMessage("Inventory service unavailable"))
        .build();
  }

  private EntityManagementServiceGrpc.EntityManagementServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }
}
