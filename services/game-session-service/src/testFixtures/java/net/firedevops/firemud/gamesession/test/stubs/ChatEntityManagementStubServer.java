package net.firedevops.firemud.gamesession.test.stubs;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Optional;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameRequest;
import net.firedevops.firemud.entitymanagement.v1.FindCharacterByNameResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamesession.test.ChatTestFixtures;
import net.firedevops.firemud.gamesession.test.LookTestFixtures;

public final class ChatEntityManagementStubServer implements AutoCloseable {
  private static final String TORCH_ITEM_ID = "torch";
  private static final String TORCH_INSTANCE_ID = "torch-ground-1";
  private static final String CAP_ITEM_ID = "leather-cap";
  private static final String CAP_INSTANCE_ID = "cap-carried-1";
  private static final String CAP_SLOT = "HEAD";

  private final Server server;
  private final int port;
  private boolean torchOnGround = true;
  private boolean capCarried = true;
  private boolean capEquipped;
  private PickupItemFromRoomRequest lastPickupRequest;
  private DropItemToRoomRequest lastDropRequest;
  private WearEquipmentItemRequest lastWearRequest;
  private RemoveEquipmentRequest lastRemoveRequest;

  public ChatEntityManagementStubServer(int port) throws IOException {
    this.port = port;
    this.server =
        ServerBuilder.forPort(port)
            .addService(
                new EntityManagementServiceGrpc.EntityManagementServiceImplBase() {
                  @Override
                  public void listRoomEntities(
                      ListRoomEntitiesRequest request,
                      StreamObserver<ListRoomEntitiesResponse> responseObserver) {
                    responseObserver.onNext(listRoomEntitiesResponse(request));
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
  }

  public String endpoint() {
    return "localhost:" + port;
  }

  public int port() {
    return port;
  }

  public synchronized void resetItemState() {
    torchOnGround = true;
    capCarried = true;
    capEquipped = false;
    lastPickupRequest = null;
    lastDropRequest = null;
    lastWearRequest = null;
    lastRemoveRequest = null;
  }

  public synchronized Optional<PickupItemFromRoomRequest> lastPickupRequest() {
    return Optional.ofNullable(lastPickupRequest);
  }

  public synchronized Optional<DropItemToRoomRequest> lastDropRequest() {
    return Optional.ofNullable(lastDropRequest);
  }

  public synchronized Optional<WearEquipmentItemRequest> lastWearRequest() {
    return Optional.ofNullable(lastWearRequest);
  }

  public synchronized Optional<RemoveEquipmentRequest> lastRemoveRequest() {
    return Optional.ofNullable(lastRemoveRequest);
  }

  private synchronized ListRoomGroundInventoryResponse listRoomGroundInventoryResponse(
      ListRoomGroundInventoryRequest request) {
    ListRoomGroundInventoryResponse.Builder response = ListRoomGroundInventoryResponse.newBuilder();
    if (torchOnGround) {
      response.addItems(torchRoomItem(request.getTenantId(), request.getGameInstanceId()));
    }
    return response.build();
  }

  private ListRoomEntitiesResponse listRoomEntitiesResponse(ListRoomEntitiesRequest request) {
    String roomInstanceId = request.getRoomInstance().getRoomInstanceId();
    return ListRoomEntitiesResponse.newBuilder()
        .setTenantId(request.getTenantId())
        .setGameInstanceId(request.getRoomInstance().getGameInstanceId())
        .setRoomInstanceId(roomInstanceId)
        .setEntitySnapshotId(LookTestFixtures.readFence(roomInstanceId))
        .addAllEntities(ChatTestFixtures.sampleEntities().getEntitiesList())
        .build();
  }

  private synchronized QueryInventoryResponse queryInventoryResponse() {
    QueryInventoryResponse.Builder response = QueryInventoryResponse.newBuilder();
    if (!torchOnGround) {
      response.addItems(torchInventoryItem());
    }
    if (capCarried) {
      response.addItems(capInventoryItem());
    }
    return response.build();
  }

  private synchronized PickupItemFromRoomResponse pickupItem(PickupItemFromRoomRequest request) {
    lastPickupRequest = request;
    torchOnGround = false;
    return PickupItemFromRoomResponse.newBuilder().setInventoryItem(torchInventoryItem()).build();
  }

  private synchronized DropItemToRoomResponse dropItem(DropItemToRoomRequest request) {
    lastDropRequest = request;
    torchOnGround = true;
    return DropItemToRoomResponse.newBuilder()
        .setRoomGroundItem(torchRoomItem(request.getTenantId(), request.getGameInstanceId()))
        .build();
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
        .setRoomInstanceId(ChatTestFixtures.ROOM_ID)
        .setItemId(TORCH_ITEM_ID)
        .setItemName("Torch")
        .setItemDescription("A small torch")
        .setQuantity(1)
        .setItemInstanceId(TORCH_INSTANCE_ID)
        .setVisibleRef("torch#1")
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
