package net.firedevops.firemud.gamelogic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentRequest;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
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
  void equipmentErrorsPropagateAsApplicationErrors() {
    WearEquipmentItemRequest request =
        WearEquipmentItemRequest.newBuilder()
            .setTenantId("1")
            .setCharacterId("7")
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
}
