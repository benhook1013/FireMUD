package net.firedevops.firemud.gamesession.client;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
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
import net.firedevops.firemud.gamelogic.v1.CommunicationTargetKind;
import net.firedevops.firemud.gamelogic.v1.CommunicationType;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveRequest;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import net.firedevops.firemud.gamelogic.v1.PingRequest;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationRequest;
import net.firedevops.firemud.gamelogic.v1.SendCommunicationResponse;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GameLogicClient
    extends AbstractBlockingGrpcClient<GameLogicServiceGrpc.GameLogicServiceBlockingStub> {
  private static final Logger LOG = LoggerFactory.getLogger(GameLogicClient.class);
  private static final long CALL_DEADLINE_SECONDS = 5L;
  private static final long READINESS_DEADLINE_SECONDS = 2L;

  private final GameplaySessionAttestationService gameplaySessionAttestationService;

  public GameLogicClient(
      ServiceEndpointsProperties endpoints,
      CommonGrpcClientProperties grpcClientProperties,
      GrpcChannelFactory channelFactory,
      BlockingGrpcStubCustomizer stubCustomizer,
      GameplaySessionAttestationService gameplaySessionAttestationService) {
    super(endpoints, grpcClientProperties, channelFactory, stubCustomizer);
    this.gameplaySessionAttestationService = gameplaySessionAttestationService;
  }

  @PostConstruct
  void init() throws Exception {
    initClient();
  }

  @Override
  protected String configuredTarget(ServiceEndpointsProperties endpoints) {
    return endpoints.getGameLogicService();
  }

  @Override
  protected String defaultTarget() {
    return "game-logic-service:6565";
  }

  @Override
  protected GameLogicServiceGrpc.GameLogicServiceBlockingStub buildStub(
      io.grpc.ManagedChannel channel) {
    return applyStubCustomizer(
        GameLogicServiceGrpc.newBlockingStub(channel).withCompression("gzip"));
  }

  public LookResult resolveLook(SessionContext context, String roomId, String localeTag) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setPreferredLocale(localeTag == null ? "" : localeTag)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    request =
        request.toBuilder()
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId,
                    sessionId,
                    Long.toString(context.accountId()),
                    characterId,
                    gameInstanceId,
                    roomId))
            .build();
    return callStub().resolveLook(request);
  }

  public LookResult resolveLookForReadiness(
      String tenantId, String sessionId, String characterId, String gameInstanceId, String roomId) {
    LookRequest request =
        LookRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId == null ? "" : gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .build();
    return stub()
        .withDeadlineAfter(READINESS_DEADLINE_SECONDS, TimeUnit.SECONDS)
        .resolveLook(request);
  }

  public SendCommunicationResponse sendCommunication(
      SessionContext context,
      String speakerName,
      String roomId,
      CommunicationType type,
      String text,
      String targetCharacterId,
      String targetCharacterName,
      String effectId) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String accountId = Long.toString(context.accountId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    SendCommunicationRequest request =
        SendCommunicationRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setAccountId(accountId)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setType(type)
            .setText(text)
            .setTargetKind(targetKindFor(type))
            .setTargetCharacterId(targetCharacterId == null ? "" : targetCharacterId)
            .setTargetCharacterName(targetCharacterName == null ? "" : targetCharacterName)
            .setGameInstanceId(gameInstanceId)
            .setSpeakerName(speakerName == null ? "" : speakerName)
            .setEffectId(effectId == null ? "" : effectId)
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId, sessionId, accountId, characterId, gameInstanceId, roomId))
            .build();
    return callStub().sendCommunication(request);
  }

  public MoveResult resolveMove(
      SessionContext context, String roomId, String direction, String localeTag) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    MoveRequest request =
        MoveRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setCharacterId(characterId)
            .setPreferredLocale(localeTag == null ? "" : localeTag)
            .setRoomInstance(
                RoomInstanceRef.newBuilder()
                    .setTenantId(tenantId)
                    .setGameInstanceId(gameInstanceId)
                    .setRoomInstanceId(roomId)
                    .build())
            .setDirection(direction)
            .setSessionAttestation(
                gameplaySessionAttestationService.issueGameplaySessionAttestation(
                    tenantId,
                    sessionId,
                    Long.toString(context.accountId()),
                    characterId,
                    gameInstanceId,
                    roomId))
            .build();
    return callStub().resolveMove(request);
  }

  public QueryInventoryResponse queryInventory(SessionContext context) {
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()))
            .build();
    try {
      return callStub().queryInventory(request);
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic inventory query failed", ex);
      return QueryInventoryResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Inventory service unavailable"))
          .build();
    }
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
            .setSessionAttestation(sessionAttestation(context, roomInstanceId))
            .build();
    try {
      return callStub().listRoomGroundInventory(request);
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic room inventory query failed", ex);
      return ListRoomGroundInventoryResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Room inventory unavailable"))
          .build();
    }
  }

  public PickupItemFromRoomResponse pickupItemFromRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    return pickupItemFromRoom(
        context,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  public PickupItemFromRoomResponse pickupItemFromRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    PickupItemFromRoomRequest.Builder request =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(Long.toString(context.characterId()))
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, roomInstanceId));
    if (StringUtils.hasText(containerInstanceId)) {
      request.setContainerInstanceId(containerInstanceId);
    }
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().pickupItemFromRoom(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic inventory pickup failed", ex);
      return PickupItemFromRoomResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Inventory service unavailable"))
          .build();
    }
  }

  public PickupItemFromRoomResponse pickupVisibleRoomItem(
      SessionContext context, String itemReference, int quantity) {
    return pickupVisibleRoomItem(context, itemReference, quantity, null);
  }

  public PickupItemFromRoomResponse pickupVisibleRoomItem(
      SessionContext context, String itemReference, int quantity, String effectId) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String accountId = Long.toString(context.accountId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    String roomInstanceId = context.roomInstanceId();
    PickupVisibleRoomItemRequest.Builder request =
        PickupVisibleRoomItemRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setAccountId(accountId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemReference(itemReference)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, roomInstanceId));
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().pickupVisibleRoomItem(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic visible room item pickup failed", ex);
      return PickupItemFromRoomResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Inventory service unavailable"))
          .build();
    }
  }

  public DropItemToRoomResponse dropItemToRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity) {
    return dropItemToRoom(
        context,
        roomInstanceId,
        itemId,
        itemInstanceId,
        containerInstanceId,
        stackFamilyKey,
        quantity,
        null);
  }

  public DropItemToRoomResponse dropItemToRoom(
      SessionContext context,
      String roomInstanceId,
      String itemId,
      String itemInstanceId,
      String containerInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    String tenantId = Long.toString(context.tenantId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    DropItemToRoomRequest.Builder request =
        DropItemToRoomRequest.newBuilder()
            .setTenantId(tenantId)
            .setCharacterId(Long.toString(context.characterId()))
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, roomInstanceId));
    if (StringUtils.hasText(containerInstanceId)) {
      request.setContainerInstanceId(containerInstanceId);
    }
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().dropItemToRoom(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic inventory drop failed", ex);
      return DropItemToRoomResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Inventory service unavailable"))
          .build();
    }
  }

  public DropItemToRoomResponse dropCarriedItem(
      SessionContext context, String itemReference, int quantity) {
    return dropCarriedItem(context, itemReference, quantity, null);
  }

  public DropItemToRoomResponse dropCarriedItem(
      SessionContext context, String itemReference, int quantity, String effectId) {
    String tenantId = Long.toString(context.tenantId());
    String sessionId = Long.toString(context.sessionId());
    String accountId = Long.toString(context.accountId());
    String characterId = Long.toString(context.characterId());
    String gameInstanceId = Long.toString(context.gameInstanceId());
    String roomInstanceId = context.roomInstanceId();
    DropCarriedItemRequest.Builder request =
        DropCarriedItemRequest.newBuilder()
            .setTenantId(tenantId)
            .setSessionId(sessionId)
            .setAccountId(accountId)
            .setCharacterId(characterId)
            .setGameInstanceId(gameInstanceId)
            .setRoomInstanceId(roomInstanceId)
            .setItemReference(itemReference)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, roomInstanceId));
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().dropCarriedItem(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic carried item drop failed", ex);
      return DropItemToRoomResponse.newBuilder()
          .setError(error("INVENTORY_UNAVAILABLE", "Inventory service unavailable"))
          .build();
    }
  }

  public ListEquipmentResponse listEquipment(SessionContext context) {
    ListEquipmentRequest request =
        ListEquipmentRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()))
            .build();
    try {
      return callStub().listEquipment(request);
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic equipment query failed", ex);
      return ListEquipmentResponse.newBuilder()
          .setError(error("EQUIPMENT_UNAVAILABLE", "Equipment service unavailable"))
          .build();
    }
  }

  public WearEquipmentItemResponse wearEquipment(
      SessionContext context, String itemId, String itemInstanceId) {
    return wearEquipment(context, itemId, itemInstanceId, null);
  }

  public WearEquipmentItemResponse wearEquipment(
      SessionContext context, String itemId, String itemInstanceId, String effectId) {
    WearEquipmentItemRequest.Builder request =
        WearEquipmentItemRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setItemId(itemId)
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()));
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().wearEquipment(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic equipment wear failed", ex);
      return WearEquipmentItemResponse.newBuilder()
          .setError(error("EQUIPMENT_UNAVAILABLE", "Equipment service unavailable"))
          .build();
    }
  }

  public RemoveEquipmentResponse removeEquipment(SessionContext context, String slot) {
    return removeEquipment(context, slot, null);
  }

  public RemoveEquipmentResponse removeEquipment(
      SessionContext context, String slot, String effectId) {
    RemoveEquipmentRequest.Builder request =
        RemoveEquipmentRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setSlot(slot)
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()));
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().removeEquipment(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic equipment remove failed", ex);
      return RemoveEquipmentResponse.newBuilder()
          .setError(error("EQUIPMENT_UNAVAILABLE", "Equipment service unavailable"))
          .build();
    }
  }

  public ListContainerContentsResponse listContainerContents(
      SessionContext context, String containerInstanceId) {
    ListContainerContentsRequest request =
        ListContainerContentsRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setContainerInstanceId(containerInstanceId)
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()))
            .build();
    try {
      return callStub().listContainerContents(request);
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic container query failed", ex);
      return ListContainerContentsResponse.newBuilder()
          .setError(error("CONTAINER_UNAVAILABLE", "Container service unavailable"))
          .build();
    }
  }

  public PutItemIntoContainerResponse putItemIntoContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return putItemIntoContainer(
        context, containerInstanceId, itemId, itemInstanceId, stackFamilyKey, quantity, null);
  }

  public PutItemIntoContainerResponse putItemIntoContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    PutItemIntoContainerRequest.Builder request =
        PutItemIntoContainerRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setContainerInstanceId(containerInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()));
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().putItemIntoContainer(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic container put failed", ex);
      return PutItemIntoContainerResponse.newBuilder()
          .setError(error("CONTAINER_UNAVAILABLE", "Container service unavailable"))
          .build();
    }
  }

  public TakeItemFromContainerResponse takeItemFromContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity) {
    return takeItemFromContainer(
        context, containerInstanceId, itemId, itemInstanceId, stackFamilyKey, quantity, null);
  }

  public TakeItemFromContainerResponse takeItemFromContainer(
      SessionContext context,
      String containerInstanceId,
      String itemId,
      String itemInstanceId,
      String stackFamilyKey,
      int quantity,
      String effectId) {
    TakeItemFromContainerRequest.Builder request =
        TakeItemFromContainerRequest.newBuilder()
            .setTenantId(Long.toString(context.tenantId()))
            .setCharacterId(Long.toString(context.characterId()))
            .setContainerInstanceId(containerInstanceId)
            .setItemId(itemId)
            .setQuantity(quantity)
            .setSessionAttestation(sessionAttestation(context, context.roomInstanceId()));
    if (StringUtils.hasText(itemInstanceId)) {
      request.setItemInstanceId(itemInstanceId);
    }
    if (StringUtils.hasText(stackFamilyKey)) {
      request.setStackFamilyKey(stackFamilyKey);
    }
    if (StringUtils.hasText(effectId)) {
      request.setEffectId(effectId);
    }
    try {
      return callStub().takeItemFromContainer(request.build());
    } catch (RuntimeException ex) {
      LOG.warn("Game Logic container take failed", ex);
      return TakeItemFromContainerResponse.newBuilder()
          .setError(error("CONTAINER_UNAVAILABLE", "Container service unavailable"))
          .build();
    }
  }

  public PingResponse ping() {
    return callStub().ping(PingRequest.getDefaultInstance());
  }

  private String sessionAttestation(SessionContext context, String roomId) {
    return gameplaySessionAttestationService.issueGameplaySessionAttestation(
        Long.toString(context.tenantId()),
        Long.toString(context.sessionId()),
        Long.toString(context.accountId()),
        Long.toString(context.characterId()),
        Long.toString(context.gameInstanceId()),
        roomId);
  }

  private ErrorDetail error(String code, String message) {
    return ErrorDetail.newBuilder().setCode(code).setMessage(message).build();
  }

  private GameLogicServiceGrpc.GameLogicServiceBlockingStub callStub() {
    return stub().withDeadlineAfter(CALL_DEADLINE_SECONDS, TimeUnit.SECONDS);
  }

  private CommunicationTargetKind targetKindFor(CommunicationType type) {
    return switch (type) {
      case SAY -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_ROOM;
      case WHISPER -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_DIRECT_CHARACTER_IN_ROOM;
      case TELL -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_DIRECT_CHARACTER;
      default -> CommunicationTargetKind.COMMUNICATION_TARGET_KIND_UNSPECIFIED;
    };
  }
}
