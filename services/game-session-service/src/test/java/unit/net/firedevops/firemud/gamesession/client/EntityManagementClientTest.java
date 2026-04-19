package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityManagementServiceGrpc;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class EntityManagementClientTest {
  private static final SessionContext ROOM_CONTEXT =
      new SessionContext(0L, 1L, 0L, "", 7L, "", 1L, "R-1", "");
  private static final SessionContext GAMEPLAY_CONTEXT =
      new SessionContext(0L, 1L, 0L, "", 7L, "", 0L, "99", "");

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
                .setGameInstanceId("1")
                .setRoomInstanceId("R-1")
                .setItemId("99")
                .setQuantity(2)
                .setSessionAttestation("attestation")
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
        client.pickupItemFromRoom(ROOM_CONTEXT, "R-1", "99", null, null, null, 2);

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
                .setGameInstanceId("1")
                .setRoomInstanceId("R-1")
                .setItemId("99")
                .setQuantity(1)
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setTenantId("1")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("R-1")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .build())
                .build());
    setStub(client, stub);

    DropItemToRoomResponse response =
        client.dropItemToRoom(ROOM_CONTEXT, "R-1", "99", null, null, null, 1);

    assertThat(response.getRoomGroundItem().getItemName()).isEqualTo("Torch");
    assertThat(response.getRoomGroundItem().getQuantity()).isEqualTo(1);
  }

  @Test
  void listContainerContentsForwardsRequestAndReturnsContainerItems() throws Exception {
    EntityManagementClient client = newClient();
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
        mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.listContainerContents(
            net.firedevops.firemud.entitymanagement.v1.ListContainerContentsRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setContainerInstanceId("99")
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("99")
                        .setItemId("100")
                        .setItemName("Torch")
                        .setQuantity(2)
                        .build())
                .build());
    setStub(client, stub);

    ListContainerContentsResponse response = client.listContainerContents(GAMEPLAY_CONTEXT, "99");

    assertThat(response.getItems(0).getItemName()).isEqualTo("Torch");
    assertThat(response.getItems(0).getQuantity()).isEqualTo(2);
  }

  @Test
  void putItemIntoContainerForwardsRequestAndReturnsContainerItem() throws Exception {
    EntityManagementClient client = newClient();
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
        mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.putItemIntoContainer(
            net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setContainerInstanceId("99")
                .setItemId("100")
                .setItemInstanceId("200")
                .setQuantity(1)
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            PutItemIntoContainerResponse.newBuilder()
                .setContainerItem(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("99")
                        .setItemId("100")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    setStub(client, stub);

    PutItemIntoContainerResponse response =
        client.putItemIntoContainer(GAMEPLAY_CONTEXT, "99", "100", "200", 1);

    assertThat(response.getContainerItem().getItemName()).isEqualTo("Torch");
    assertThat(response.getContainerItem().getQuantity()).isEqualTo(1);
  }

  @Test
  void takeItemFromContainerForwardsRequestAndReturnsInventoryItem() throws Exception {
    EntityManagementClient client = newClient();
    EntityManagementServiceGrpc.EntityManagementServiceBlockingStub stub =
        mock(EntityManagementServiceGrpc.EntityManagementServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.takeItemFromContainer(
            net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerRequest.newBuilder()
                .setTenantId("1")
                .setCharacterId("7")
                .setContainerInstanceId("99")
                .setItemId("100")
                .setItemInstanceId("200")
                .setQuantity(1)
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            TakeItemFromContainerResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("100")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    setStub(client, stub);

    TakeItemFromContainerResponse response =
        client.takeItemFromContainer(GAMEPLAY_CONTEXT, "99", "100", "200", 1);

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Torch");
    assertThat(response.getInventoryItem().getQuantity()).isEqualTo(1);
  }

  private static EntityManagementClient newClient() {
    GameplaySessionAttestationService attestationService =
        mock(GameplaySessionAttestationService.class);
    when(attestationService.issueGameplaySessionAttestation("1", "0", "0", "7", "1", "R-1"))
        .thenReturn("attestation");
    when(attestationService.issueGameplaySessionAttestation("1", "0", "0", "7", "0", ""))
        .thenReturn("attestation");
    when(attestationService.issueGameplaySessionAttestation("1", "0", "0", "7", "0", "99"))
        .thenReturn("attestation");
    return new EntityManagementClient(
        new ServiceEndpointsProperties(),
        new CommonGrpcClientProperties(),
        mock(GrpcChannelFactory.class),
        BlockingGrpcStubCustomizer.noop(),
        attestationService);
  }

  private static void setStub(EntityManagementClient client, Object stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
