package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import org.junit.jupiter.api.Test;

class EntityManagementClientTest {
  @Test
  void pickupItemFromRoomForwardsRequestAndReturnsInventoryItem() throws Exception {
    EntityManagementClient client = newClient();
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
        mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.pickupItemFromRoom(
            net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setGameInstanceId("GI-1")
                .setRoomInstanceId("R-1")
                .setItemId("99")
                .setQuantity(2)
                .build()))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());
    setStub(client, stub);

    PickupItemFromRoomResponse response =
        client.pickupItemFromRoom("1", "7", "GI-1", "R-1", "99", 2);

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Torch");
    assertThat(response.getInventoryItem().getQuantity()).isEqualTo(2);
  }

  @Test
  void dropItemToRoomForwardsRequestAndReturnsRoomGroundItem() throws Exception {
    EntityManagementClient client = newClient();
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
        mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.dropItemToRoom(
            net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setGameInstanceId("GI-1")
                .setRoomInstanceId("R-1")
                .setItemId("99")
                .setQuantity(1)
                .build()))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("GI-1")
                        .setRoomInstanceId("R-1")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .build())
                .build());
    setStub(client, stub);

    DropItemToRoomResponse response = client.dropItemToRoom("1", "7", "GI-1", "R-1", "99", 1);

    assertThat(response.getRoomGroundItem().getItemName()).isEqualTo("Torch");
    assertThat(response.getRoomGroundItem().getQuantity()).isEqualTo(1);
  }

  private static EntityManagementClient newClient() {
    return new EntityManagementClient(
        new ServiceEndpointsProperties(),
        new CommonGrpcClientProperties(),
        mock(GrpcChannelFactory.class));
  }

  private static void setStub(EntityManagementClient client, Object stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
