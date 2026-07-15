package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
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
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryActorStateResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;
import net.firedevops.firemud.shared.v1.ErrorDetail;

public final class EntityManagementStubServer implements AutoCloseable {
  private static final String TORCH_ITEM_ID = "torch";
  private static final String TORCH_INSTANCE_ID = "torch-ground-1";
  private static final String CAP_ITEM_ID = "leather-cap";
  private static final String CAP_INSTANCE_ID = "cap-carried-1";
  private static final String BOOTS_ITEM_ID = "iron-boots";
  private static final String BOOTS_INSTANCE_ID = "boots-carried-1";
  private static final String CAP_SLOT = "HEAD";
  private static final String BACKPACK_ITEM_ID = "backpack";
  private static final String BACKPACK_INSTANCE_ID = "backpack-ground-1";
  private static final String BACKPACK_CONTAINER_ID = "container-backpack-1";
  private static final String RATION_ITEM_ID = "ration";
  private static final String RATION_INSTANCE_ID = "ration-contained-1";

  private final Server server;
  private final int port;
  private final AtomicReference<ListRoomEntitiesResponse> roomEntities =
      new AtomicReference<>(LookTestFixtures.sampleEntities());
  private final AtomicReference<QueryActorStateResponse> actorState =
      new AtomicReference<>(QueryActorStateResponse.getDefaultInstance());
  private boolean torchOnGround = true;
  private boolean torchInBackpack;
  private boolean capCarried = true;
  private boolean bootsCarried = true;
  private boolean capEquipped;
  private PickupItemFromRoomRequest lastPickupRequest;
  private DropItemToRoomRequest lastDropRequest;
  private PutItemIntoContainerRequest lastPutRequest;
  private TakeItemFromContainerRequest lastTakeRequest;
  private WearEquipmentItemRequest lastWearRequest;
  private RemoveEquipmentRequest lastRemoveRequest;

  public EntityManagementStubServer(int port) throws IOException {
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new EntityManagementServiceGrpc.EntityManagementServiceImplBase() {
                  @Override
                  public void listRoomEntities(
                      ListRoomEntitiesRequest request,
                      StreamObserver<ListRoomEntitiesResponse> responseObserver) {
                    String roomInstanceId = request.getRoomInstance().getRoomInstanceId();
                    responseObserver.onNext(stampReadFence(roomEntities.get(), roomInstanceId));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void findCharacterByName(
                      FindCharacterByNameRequest request,
                      StreamObserver<FindCharacterByNameResponse> responseObserver) {
                    FindCharacterByNameResponse.Builder builder =
                        FindCharacterByNameResponse.newBuilder();
                    var character = ChatTestFixtures.characterByName(request.getName());
                    if (!character.equals(character.getDefaultInstanceForType())) {
                      builder.setCharacter(character);
                    }
                    responseObserver.onNext(builder.build());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listRoomGroundInventory(
                      ListRoomGroundInventoryRequest request,
                      StreamObserver<ListRoomGroundInventoryResponse> responseObserver) {
                    responseObserver.onNext(listRoomGroundInventoryResponse(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void queryInventory(
                      QueryInventoryRequest request,
                      StreamObserver<QueryInventoryResponse> responseObserver) {
                    responseObserver.onNext(queryInventoryResponse());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void queryActorState(
                      QueryActorStateRequest request,
                      StreamObserver<QueryActorStateResponse> responseObserver) {
                    responseObserver.onNext(actorState.get());
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void pickupItemFromRoom(
                      PickupItemFromRoomRequest request,
                      StreamObserver<PickupItemFromRoomResponse> responseObserver) {
                    responseObserver.onNext(pickupItem(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void dropItemToRoom(
                      DropItemToRoomRequest request,
                      StreamObserver<DropItemToRoomResponse> responseObserver) {
                    responseObserver.onNext(dropItem(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listEquipment(
                      ListEquipmentRequest request,
                      StreamObserver<ListEquipmentResponse> responseObserver) {
                    responseObserver.onNext(listEquipmentResponse(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void listContainerContents(
                      ListContainerContentsRequest request,
                      StreamObserver<ListContainerContentsResponse> responseObserver) {
                    responseObserver.onNext(listContainerContentsResponse(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void putItemIntoContainer(
                      PutItemIntoContainerRequest request,
                      StreamObserver<PutItemIntoContainerResponse> responseObserver) {
                    responseObserver.onNext(putItemIntoContainerResponse(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void takeItemFromContainer(
                      TakeItemFromContainerRequest request,
                      StreamObserver<TakeItemFromContainerResponse> responseObserver) {
                    responseObserver.onNext(takeItemFromContainerResponse(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void wearEquipment(
                      WearEquipmentItemRequest request,
                      StreamObserver<WearEquipmentItemResponse> responseObserver) {
                    responseObserver.onNext(wearEquipmentItem(request));
                    responseObserver.onCompleted();
                  }

                  @Override
                  public void removeEquipment(
                      RemoveEquipmentRequest request,
                      StreamObserver<RemoveEquipmentResponse> responseObserver) {
                    responseObserver.onNext(removeEquipmentItem(request));
                    responseObserver.onCompleted();
                  }
                })
            .build()
            .start();
    this.port = server.getPort();
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  public void setRoomEntities(ListRoomEntitiesResponse roomEntities) {
    this.roomEntities.set(roomEntities);
  }

  public void resetRoomEntities() {
    roomEntities.set(LookTestFixtures.sampleEntities());
  }

  public void setActorState(QueryActorStateResponse response) {
    actorState.set(response == null ? QueryActorStateResponse.getDefaultInstance() : response);
  }

  public void resetActorState() {
    actorState.set(QueryActorStateResponse.getDefaultInstance());
  }

  public synchronized void resetItemState() {
    torchOnGround = true;
    torchInBackpack = false;
    capCarried = true;
    bootsCarried = true;
    capEquipped = false;
    lastPickupRequest = null;
    lastDropRequest = null;
    lastPutRequest = null;
    lastTakeRequest = null;
    lastWearRequest = null;
    lastRemoveRequest = null;
  }

  public synchronized Optional<PickupItemFromRoomRequest> lastPickupRequest() {
    return Optional.ofNullable(lastPickupRequest);
  }

  public synchronized Optional<DropItemToRoomRequest> lastDropRequest() {
    return Optional.ofNullable(lastDropRequest);
  }

  public synchronized Optional<PutItemIntoContainerRequest> lastPutRequest() {
    return Optional.ofNullable(lastPutRequest);
  }

  public synchronized Optional<TakeItemFromContainerRequest> lastTakeRequest() {
    return Optional.ofNullable(lastTakeRequest);
  }

  public synchronized Optional<WearEquipmentItemRequest> lastWearRequest() {
    return Optional.ofNullable(lastWearRequest);
  }

  public synchronized Optional<RemoveEquipmentRequest> lastRemoveRequest() {
    return Optional.ofNullable(lastRemoveRequest);
  }

  private ListRoomEntitiesResponse stampReadFence(
      ListRoomEntitiesResponse response, String roomInstanceId) {
    return ListRoomEntitiesResponse.newBuilder()
        .setTenantId(LookTestFixtures.TENANT)
        .setGameInstanceId(LookTestFixtures.GAME_INSTANCE_ID)
        .setRoomInstanceId(roomInstanceId)
        .setEntitySnapshotId(LookTestFixtures.readFence(roomInstanceId))
        .addAllEntities(response.getEntitiesList())
        .build();
  }

  private synchronized ListRoomGroundInventoryResponse listRoomGroundInventoryResponse(
      ListRoomGroundInventoryRequest request) {
    ListRoomGroundInventoryResponse.Builder response = ListRoomGroundInventoryResponse.newBuilder();
    if (torchOnGround) {
      response.addItems(torchRoomItem(request.getTenantId(), request.getGameInstanceId()));
    }
    response.addItems(backpackRoomItem(request.getTenantId(), request.getGameInstanceId()));
    return response.build();
  }

  private synchronized QueryInventoryResponse queryInventoryResponse() {
    QueryInventoryResponse.Builder response = QueryInventoryResponse.newBuilder();
    if (!torchOnGround && !torchInBackpack) {
      response.addItems(torchInventoryItem());
    }
    if (capCarried) {
      response.addItems(capInventoryItem());
    }
    if (bootsCarried) {
      response.addItems(bootsInventoryItem());
    }
    return response.build();
  }

  private synchronized PickupItemFromRoomResponse pickupItem(PickupItemFromRoomRequest request) {
    lastPickupRequest = request;
    torchOnGround = false;
    torchInBackpack = false;
    return PickupItemFromRoomResponse.newBuilder().setInventoryItem(torchInventoryItem()).build();
  }

  private synchronized DropItemToRoomResponse dropItem(DropItemToRoomRequest request) {
    lastDropRequest = request;
    torchOnGround = true;
    torchInBackpack = false;
    return DropItemToRoomResponse.newBuilder()
        .setRoomGroundItem(torchRoomItem(request.getTenantId(), request.getGameInstanceId()))
        .build();
  }

  private synchronized ListContainerContentsResponse listContainerContentsResponse(
      ListContainerContentsRequest request) {
    ListContainerContentsResponse.Builder response = ListContainerContentsResponse.newBuilder();
    if (BACKPACK_CONTAINER_ID.equals(request.getContainerInstanceId())) {
      response.addItems(rationContainerItem(request.getTenantId(), request.getCharacterId()));
      if (torchInBackpack) {
        response.addItems(torchContainerItem(request.getTenantId(), request.getCharacterId()));
      }
    }
    return response.build();
  }

  private synchronized PutItemIntoContainerResponse putItemIntoContainerResponse(
      PutItemIntoContainerRequest request) {
    lastPutRequest = request;
    if (BACKPACK_CONTAINER_ID.equals(request.getContainerInstanceId())
        && TORCH_ITEM_ID.equals(request.getItemId())) {
      torchOnGround = false;
      torchInBackpack = true;
      return PutItemIntoContainerResponse.newBuilder()
          .setContainerItem(torchContainerItem(request.getTenantId(), request.getCharacterId()))
          .build();
    }
    return PutItemIntoContainerResponse.newBuilder().build();
  }

  private synchronized TakeItemFromContainerResponse takeItemFromContainerResponse(
      TakeItemFromContainerRequest request) {
    lastTakeRequest = request;
    if (BACKPACK_CONTAINER_ID.equals(request.getContainerInstanceId())
        && TORCH_ITEM_ID.equals(request.getItemId())) {
      torchInBackpack = false;
      return TakeItemFromContainerResponse.newBuilder()
          .setInventoryItem(torchInventoryItem())
          .build();
    }
    return TakeItemFromContainerResponse.newBuilder().build();
  }

  private synchronized ListEquipmentResponse listEquipmentResponse(ListEquipmentRequest request) {
    ListEquipmentResponse.Builder response = ListEquipmentResponse.newBuilder();
    if (capEquipped) {
      response.addItems(capEquipmentItem(request.getTenantId(), request.getCharacterId()));
    }
    return response.build();
  }

  private synchronized WearEquipmentItemResponse wearEquipmentItem(
      WearEquipmentItemRequest request) {
    lastWearRequest = request;
    if (BOOTS_ITEM_ID.equals(request.getItemId())) {
      return WearEquipmentItemResponse.newBuilder()
          .setError(
              ErrorDetail.newBuilder()
                  .setCode("SLOT_INCOMPATIBLE")
                  .setMessage("Iron Boots cannot be worn by this body layout."))
          .build();
    }
    capCarried = false;
    capEquipped = true;
    return WearEquipmentItemResponse.newBuilder()
        .setEquipmentItem(capEquipmentItem(request.getTenantId(), request.getCharacterId()))
        .build();
  }

  private synchronized RemoveEquipmentResponse removeEquipmentItem(RemoveEquipmentRequest request) {
    lastRemoveRequest = request;
    capCarried = true;
    capEquipped = false;
    return RemoveEquipmentResponse.newBuilder()
        .setEquipmentItem(capEquipmentItem(request.getTenantId(), request.getCharacterId()))
        .build();
  }

  private RoomGroundInventoryItem torchRoomItem(String tenantId, String gameInstanceId) {
    return RoomGroundInventoryItem.newBuilder()
        .setTenantId(tenantId)
        .setGameInstanceId(gameInstanceId)
        .setRoomInstanceId(LookTestFixtures.ROOM_ID)
        .setItemId(TORCH_ITEM_ID)
        .setItemName("Torch")
        .setItemDescription("A small torch")
        .setQuantity(1)
        .setItemInstanceId(TORCH_INSTANCE_ID)
        .setVisibleRef("torch#1")
        .build();
  }

  private RoomGroundInventoryItem backpackRoomItem(String tenantId, String gameInstanceId) {
    return RoomGroundInventoryItem.newBuilder()
        .setTenantId(tenantId)
        .setGameInstanceId(gameInstanceId)
        .setRoomInstanceId(LookTestFixtures.ROOM_ID)
        .setItemId(BACKPACK_ITEM_ID)
        .setItemName("Backpack")
        .setItemDescription("A weathered backpack")
        .setQuantity(1)
        .setContainerInstanceId(BACKPACK_CONTAINER_ID)
        .setItemInstanceId(BACKPACK_INSTANCE_ID)
        .setVisibleRef("backpack#1")
        .build();
  }

  private InventoryItem torchInventoryItem() {
    return InventoryItem.newBuilder()
        .setItemId(TORCH_ITEM_ID)
        .setItemName("Torch")
        .setItemDescription("A small torch")
        .setQuantity(1)
        .setItemInstanceId(TORCH_INSTANCE_ID)
        .setVisibleRef("torch#1")
        .build();
  }

  private InventoryItem capInventoryItem() {
    return InventoryItem.newBuilder()
        .setItemId(CAP_ITEM_ID)
        .setItemName("Leather Cap")
        .setItemDescription("A small cap")
        .setQuantity(1)
        .setItemInstanceId(CAP_INSTANCE_ID)
        .setVisibleRef("cap#1")
        .build();
  }

  private InventoryItem bootsInventoryItem() {
    return InventoryItem.newBuilder()
        .setItemId(BOOTS_ITEM_ID)
        .setItemName("Iron Boots")
        .setItemDescription("Heavy iron boots")
        .setQuantity(1)
        .setItemInstanceId(BOOTS_INSTANCE_ID)
        .setVisibleRef("boots#1")
        .build();
  }

  private ContainerItem rationContainerItem(String tenantId, String characterId) {
    return ContainerItem.newBuilder()
        .setTenantId(tenantId)
        .setCharacterId(characterId)
        .setContainerInstanceId(BACKPACK_CONTAINER_ID)
        .setItemId(RATION_ITEM_ID)
        .setItemName("Ration")
        .setItemDescription("A dry trail ration")
        .setQuantity(1)
        .setItemInstanceId(RATION_INSTANCE_ID)
        .setVisibleRef("ration#1")
        .build();
  }

  private ContainerItem torchContainerItem(String tenantId, String characterId) {
    return ContainerItem.newBuilder()
        .setTenantId(tenantId)
        .setCharacterId(characterId)
        .setContainerInstanceId(BACKPACK_CONTAINER_ID)
        .setItemId(TORCH_ITEM_ID)
        .setItemName("Torch")
        .setItemDescription("A small torch")
        .setQuantity(1)
        .setItemInstanceId(TORCH_INSTANCE_ID)
        .setVisibleRef("torch#1")
        .build();
  }

  private EquipmentItem capEquipmentItem(String tenantId, String characterId) {
    return EquipmentItem.newBuilder()
        .setTenantId(tenantId)
        .setCharacterId(characterId)
        .setSlot(CAP_SLOT)
        .setItemId(CAP_ITEM_ID)
        .setItemName("Leather Cap")
        .setItemDescription("A small cap")
        .setItemInstanceId(CAP_INSTANCE_ID)
        .setVisibleRef("cap#1")
        .build();
  }

  @Override
  public void close() {
    if (server != null) {
      server.shutdownNow();
    }
  }
}
