package net.firedevops.firemud.gamesession.client;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PostConstruct;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountRequest;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PingRequest;
import net.firedevops.firemud.entitymanagement.v1.PingResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** gRPC client for the Entity Management Service. */
@Component
public final class EntityManagementClient
    extends AbstractBlockingGrpcClient<
        EntityManagementServiceGrpc.EntityManagementServiceBlockingStub> {
  private static final Logger logger = LoggerFactory.getLogger(EntityManagementClient.class);
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long FIND_CHARACTER_DEADLINE_MILLIS = 500L;
  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  public EntityManagementClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties tlsProps,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      GameplaySessionAttestationService gameplaySessionAttestationService) {
    super(endpoints, tlsProps, channelFactory, stubCustomizer);
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
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
    return applyStubCustomizer(
        EntityManagementServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  /** Simple ping to verify connectivity. */
  public PingResponse ping() {
    return callStub().ping(PingRequest.newBuilder().build());
  }

  public Optional<Character> findCharacterByName(
      SessionContext context, PlayableStateScope playableStateScope, String name) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    FindCharacterByNameRequest request =
        FindCharacterByNameRequest.newBuilder()
            .setTenantId(tenantId)
            .setGameInstanceId(gameInstanceId)
            .setPlayableStateScope(playableStateScope)
            .setName(name)
            .setSessionAttestation(
                sessionAttestation(context, gameInstanceId, context.roomInstanceId()))
            .build();
    try {
      FindCharacterByNameResponse response =
          stub()
              .withDeadlineAfter(FIND_CHARACTER_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
              .findCharacterByName(request);
      if (response.hasError() || !response.hasCharacter()) {
        return Optional.empty();
      }
      return Optional.of(response.getCharacter());
    } catch (StatusRuntimeException ex) {
      logger.debug(
          "Entity Management character lookup failed tenantId={} name={}", tenantId, name, ex);
      return Optional.empty();
    } catch (Exception ex) {
      logger.debug(
          "Entity Management character lookup failed unexpectedly tenantId={} name={}",
          tenantId,
          name,
          ex);
      return Optional.empty();
    }
  }

  public ListCharactersByAccountResponse listCharactersByAccount(
      String tenantId,
      String accountId,
      String gameInstanceId,
      PlayableStateScope playableStateScope) {
    ListCharactersByAccountRequest request =
        ListCharactersByAccountRequest.newBuilder()
            .setTenantId(tenantId)
            .setAccountId(accountId)
            .setGameInstanceId(gameInstanceId)
            .setPlayableStateScope(playableStateScope)
            .build();
    try {
      return callStub().listCharactersByAccount(request);
    } catch (StatusRuntimeException ex) {
      logger.warn(
          "Failed to call Entity Management list-characters endpoint tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
    } catch (Exception ex) {
      logger.warn(
          "Failed to call Entity Management list-characters endpoint tenantId={} accountId={}",
          tenantId,
          accountId,
          ex);
    }
    return ListCharactersByAccountResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("CHARACTER_LIST_UNAVAILABLE")
                .setMessage("Character list unavailable"))
        .build();
  }

  public QueryInventoryResponse queryInventory(SessionContext context) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()))
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

  public ListEquipmentResponse listEquipment(SessionContext context) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    ListEquipmentRequest request =
        ListEquipmentRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()))
            .build();
    try {
      return callStub().listEquipment(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying equipment query",
            ex);
        try {
          initClient();
          return callStub().listEquipment(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management equipment query after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management equipment query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management equipment query endpoint", ex);
    }
    return ListEquipmentResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("EQUIPMENT_UNAVAILABLE")
                .setMessage("Equipment service unavailable"))
        .build();
  }

  public ListContainerContentsResponse listContainerContents(
      SessionContext context, String containerInstanceId) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    ListContainerContentsRequest request =
        ListContainerContentsRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setContainerInstanceId(containerInstanceId)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()))
            .build();
    try {
      return callStub().listContainerContents(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying container query",
            ex);
        try {
          initClient();
          return callStub().listContainerContents(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management container query after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management container query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management container query endpoint", ex);
    }
    return ListContainerContentsResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("CONTAINER_UNAVAILABLE")
                .setMessage("Container service unavailable"))
        .build();
  }

  public ListRoomGroundInventoryResponse listRoomGroundInventory(
      SessionContext context, String roomInstanceId) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    ListRoomGroundInventoryRequest request =
        ListRoomGroundInventoryRequest.newBuilder()
            .setTenantId(tenantId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setSessionAttestation(sessionAttestation(context, gameInstanceId, roomInstanceId))
            .build();
    try {
      return callStub().listRoomGroundInventory(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying room inventory query",
            ex);
        try {
          initClient();
          return callStub().listRoomGroundInventory(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management room inventory query after channel reload",
              retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management room inventory query endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management room inventory query endpoint", ex);
    }
    return ListRoomGroundInventoryResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("INVENTORY_UNAVAILABLE")
                .setMessage("Room inventory unavailable"))
        .build();
  }

  public WearEquipmentItemResponse wearEquipment(
      SessionContext context, String itemId, String itemInstanceId) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    WearEquipmentItemRequest.Builder request =
        WearEquipmentItemRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setItemId(itemId)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()));
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    try {
      return callStub().wearEquipment(request.build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying equipment wear",
            ex);
        try {
          initClient();
          return callStub().wearEquipment(request.build());
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management equipment wear after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management equipment wear endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management equipment wear endpoint", ex);
    }
    return WearEquipmentItemResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("EQUIPMENT_UNAVAILABLE")
                .setMessage("Equipment service unavailable"))
        .build();
  }

  public WearEquipmentItemResponse wearEquipment(SessionContext context, String itemId) {
    return wearEquipment(context, itemId, null);
  }

  public RemoveEquipmentResponse removeEquipment(SessionContext context, String slot) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    RemoveEquipmentRequest request =
        RemoveEquipmentRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setSlot(slot)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()))
            .build();
    try {
      return callStub().removeEquipment(request);
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying equipment remove",
            ex);
        try {
          initClient();
          return callStub().removeEquipment(request);
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management equipment remove after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management equipment remove endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management equipment remove endpoint", ex);
    }
    return RemoveEquipmentResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("EQUIPMENT_UNAVAILABLE")
                .setMessage("Equipment service unavailable"))
        .build();
  }

  public PutItemIntoContainerResponse putItemIntoContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    PutItemIntoContainerRequest.Builder request =
        PutItemIntoContainerRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setContainerInstanceId(containerInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()));
    if (itemInstanceId != null && !itemInstanceId.isBlank()) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (stackFamilyKey != null && !stackFamilyKey.isBlank()) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    try {
      return callStub().putItemIntoContainer(request.build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying container put",
            ex);
        try {
          initClient();
          return callStub().putItemIntoContainer(request.build());
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management container put after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management container put endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management container put endpoint", ex);
    }
    return PutItemIntoContainerResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("CONTAINER_UNAVAILABLE")
                .setMessage("Container service unavailable"))
        .build();
  }

  public PutItemIntoContainerResponse putItemIntoContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      int quantity) {
    return putItemIntoContainer(
        context, containerInstanceId, itemId, itemInstanceId, null, quantity);
  }

  public TakeItemFromContainerResponse takeItemFromContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    TakeItemFromContainerRequest.Builder request =
        TakeItemFromContainerRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setContainerInstanceId(containerInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(
                sessionAttestation(
                    context, Long.toString(context.gameInstanceId()), context.roomInstanceId()));
    if (itemInstanceId != null && !itemInstanceId.isBlank()) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (stackFamilyKey != null && !stackFamilyKey.isBlank()) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    try {
      return callStub().takeItemFromContainer(request.build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying container take",
            ex);
        try {
          initClient();
          return callStub().takeItemFromContainer(request.build());
        } catch (Exception retryEx) {
          logger.warn(
              "Failed to retry Entity Management container take after channel reload", retryEx);
        }
      } else {
        logger.warn("Failed to call Entity Management container take endpoint", ex);
      }
    } catch (Exception ex) {
      logger.warn("Failed to call Entity Management container take endpoint", ex);
    }
    return TakeItemFromContainerResponse.newBuilder()
        .setError(
            ErrorDetail.newBuilder()
                .setCode("CONTAINER_UNAVAILABLE")
                .setMessage("Container service unavailable"))
        .build();
  }

  public TakeItemFromContainerResponse takeItemFromContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      int quantity) {
    return takeItemFromContainer(
        context, containerInstanceId, itemId, itemInstanceId, null, quantity);
  }

  public ListRoomEntitiesResponse listRoomEntities(SessionContext context, String roomInstanceId) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    ListRoomEntitiesRequest request =
        ListRoomEntitiesRequest.newBuilder()
            .setTenantId(tenantId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomInstanceId)
                    .build())
            .setSessionAttestation(sessionAttestation(context, gameInstanceId, roomInstanceId))
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
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    PickupItemFromRoomRequest.Builder request =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, gameInstanceId, roomInstanceId));
    if (StringUtils.hasText(containerInstanceId)) {
      request.setContainerInstanceId(containerInstanceId);
    }
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    try {
      return callStub().pickupItemFromRoom(request.build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying inventory pickup",
            ex);
        try {
          initClient();
          return callStub().pickupItemFromRoom(request.build());
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

  public PickupItemFromRoomResponse pickupItemFromRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      int quantity) {
    return pickupItemFromRoom(
        context, roomInstanceId, itemId, itemInstanceId, containerInstanceId, null, quantity);
  }

  public PickupItemFromRoomResponse pickupItemFromRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String containerInstanceId,
      int quantity) {
    return pickupItemFromRoom(
        context, roomInstanceId, itemId, null, containerInstanceId, null, quantity);
  }

  public DropItemToRoomResponse dropItemToRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    String tenantId = Long.toString(context.tenantId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    DropItemToRoomRequest.Builder request =
        DropItemToRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, gameInstanceId, roomInstanceId));
    if (StringUtils.hasText(containerInstanceId)) {
      request.setContainerInstanceId(containerInstanceId);
    }
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    try {
      return callStub().dropItemToRoom(request.build());
    } catch (StatusRuntimeException ex) {
      if (ex.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        logger.warn(
            "Entity Management Service unavailable; rebuilding channel and retrying inventory drop",
            ex);
        try {
          initClient();
          return callStub().dropItemToRoom(request.build());
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

  public DropItemToRoomResponse dropItemToRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      int quantity) {
    return dropItemToRoom(
        context, roomInstanceId, itemId, itemInstanceId, containerInstanceId, null, quantity);
  }

  public DropItemToRoomResponse dropItemToRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String containerInstanceId,
      int quantity) {
    return dropItemToRoom(
        context, roomInstanceId, itemId, null, containerInstanceId, null, quantity);
  }

  private EntityManagementServiceGrpc.EntityManagementServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private String sessionAttestation(
      SessionContext context, String gameInstanceId, String roomInstanceId) {
    return gameplaySessionAttestationService.issueGameplaySessionAttestation(
        Long.toString(context.tenantId()),
        Long.toString(context.sessionId()),
        Long.toString(context.accountId()),
        Long.toString(context.characterId()),
        gameInstanceId,
        roomInstanceId);
  }
}
