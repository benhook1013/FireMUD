package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.entitymanagement.v1.ActorConditionState;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionRequest;
import net.firedevops.firemud.entitymanagement.v1.ApplyActorConditionResponse;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ItemRuntimeServiceTest {
  private final EntityManagementServiceGrpc.EntityManagementServiceBlockingStub entityStub =
      Mockito.mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
  private final ItemRuntimeService service =
      new ItemRuntimeService(entityStub, new SimpleMeterRegistry());

  @Test
  void queryInventoryDelegatesToEntityRuntime() {
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.queryInventory(request))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(InventoryItem.newBuilder().setItemName("Torch").build())
                .build());

    QueryInventoryResponse response = service.queryInventory(request);

    assertThat(response.getItemsList())
        .extracting(InventoryItem::getItemName)
        .containsExactly("Torch");
  }

  @Test
  void pickupAndDropPreserveEntityRuntimeResponses() {
    PickupItemFromRoomRequest pickupRequest =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("room-1")
            .setItemId("100")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.pickupItemFromRoom(pickupRequest))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(InventoryItem.newBuilder().setItemName("Torch").build())
                .build());
    DropItemToRoomRequest dropRequest =
        DropItemToRoomRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("room-1")
            .setItemId("100")
            .setQuantity(1)
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.dropItemToRoom(dropRequest))
        .thenReturn(DropItemToRoomResponse.newBuilder().build());

    assertThat(service.pickupItemFromRoom(pickupRequest).getInventoryItem().getItemName())
        .isEqualTo("Torch");
    assertThat(service.dropItemToRoom(dropRequest).hasError()).isFalse();
  }

  @Test
  void applyActorConditionDelegatesToEntityRuntime() {
    ApplyActorConditionRequest request =
        ApplyActorConditionRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setConditionKey("blocking")
            .setSourceType("ACTION_STATE")
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.applyActorCondition(request))
        .thenReturn(
            ApplyActorConditionResponse.newBuilder()
                .setActiveCondition(
                    ActorConditionState.newBuilder().setConditionKey("blocking").build())
                .build());

    ApplyActorConditionResponse response = service.applyActorCondition(request);

    assertThat(response.getActiveCondition().getConditionKey()).isEqualTo("blocking");
  }

  @Test
  void equipmentErrorsPropagateAsApplicationErrors() {
    WearEquipmentItemRequest request =
        WearEquipmentItemRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("100")
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.wearEquipment(request))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode("SLOT_INCOMPATIBLE")
                        .setMessage("not compatible"))
                .build());

    WearEquipmentItemResponse response = service.wearEquipment(request);

    assertThat(response.getError().getCode()).isEqualTo("SLOT_INCOMPATIBLE");
    assertThat(response.getError().getMessage()).isEqualTo("not compatible");
  }

  @Test
  void backendTransportFailuresBecomeApplicationErrors() {
    ListEquipmentRequest request =
        ListEquipmentRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.listEquipment(request))
        .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("down")));

    ListEquipmentResponse response = service.listEquipment(request);

    assertThat(response.getError().getCode()).isEqualTo("EQUIPMENT_UNAVAILABLE");
    assertThat(response.getError().getMessage()).isEqualTo("down");
  }

  @Test
  void successfulEquipFlowReturnsEquipmentItem() {
    WearEquipmentItemRequest request =
        WearEquipmentItemRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setItemId("100")
            .setSessionAttestation("attestation")
            .build();
    when(entityStub.wearEquipment(request))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setEquipmentItem(EquipmentItem.newBuilder().setSlot("HEAD").build())
                .build());

    assertThat(service.wearEquipment(request).getEquipmentItem().getSlot()).isEqualTo("HEAD");
  }

  @Test
  void pickupVisibleRoomItemResolvesReferenceBeforeDelegatingToEntityRuntime() {
    PickupVisibleRoomItemRequest request =
        PickupVisibleRoomItemRequest.newBuilder()
            .setTenantId("1")
            .setSessionId("99")
            .setAccountId("3")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setRoomInstanceId("room-1")
            .setItemReference("torch1")
            .setQuantity(1)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(entityStub.listRoomGroundInventory(
            ListRoomGroundInventoryRequest.newBuilder()
                .setTenantId("1")
                .setGameInstanceId("9")
                .setRoomInstanceId("room-1")
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("100")
                        .setItemInstanceId("500")
                        .setContainerInstanceId("ground-container-1")
                        .setVisibleRef("torch1")
                        .setItemName("Torch")
                        .build())
                .build());
    when(entityStub.pickupItemFromRoom(
            PickupItemFromRoomRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setGameInstanceId("9")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setRoomInstanceId("room-1")
                .setItemId("100")
                .setItemInstanceId("500")
                .setContainerInstanceId("ground-container-1")
                .setQuantity(1)
                .setSessionAttestation("attestation")
                .setEffectId("effect-1")
                .build()))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(InventoryItem.newBuilder().setItemName("Torch"))
                .build());

    PickupItemFromRoomResponse response = service.pickupVisibleRoomItem(request);

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Torch");
  }

  @Test
  void dropCarriedItemResolvesStackReferenceBeforeDelegatingToEntityRuntime() {
    DropCarriedItemRequest request =
        DropCarriedItemRequest.newBuilder()
            .setTenantId("1")
            .setSessionId("99")
            .setAccountId("3")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setRoomInstanceId("room-1")
            .setItemReference("ammo/iron")
            .setQuantity(3)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(entityStub.queryInventory(
            QueryInventoryRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setGameInstanceId("9")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("100")
                        .setVisibleRef("ammo/iron")
                        .setItemName("Arrow")
                        .setQuantity(12)
                        .build())
                .build());
    when(entityStub.dropItemToRoom(
            DropItemToRoomRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setGameInstanceId("9")
                .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
                .setRoomInstanceId("room-1")
                .setItemId("100")
                .setContainerInstanceId("100")
                .setStackFamilyKey("ammo/iron")
                .setQuantity(3)
                .setSessionAttestation("attestation")
                .setEffectId("effect-1")
                .build()))
        .thenReturn(DropItemToRoomResponse.newBuilder().build());

    DropItemToRoomResponse response = service.dropCarriedItem(request);

    assertThat(response.hasError()).isFalse();
  }

  @Test
  void pickupVisibleRoomItemRejectsMissingReferenceAsApplicationError() {
    PickupVisibleRoomItemRequest request =
        PickupVisibleRoomItemRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
            .setGameInstanceId("9")
            .setRoomInstanceId("room-1")
            .setQuantity(1)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .build();

    PickupItemFromRoomResponse response = service.pickupVisibleRoomItem(request);

    assertThat(response.getError().getCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(response.getError().getMessage()).isEqualTo("GET command requires an item");
  }
}
