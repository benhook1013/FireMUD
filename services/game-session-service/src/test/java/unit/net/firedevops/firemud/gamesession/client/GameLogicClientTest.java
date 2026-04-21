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
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.Test;

class GameLogicClientTest {
  private static final SessionContext SESSION_CONTEXT =
      new SessionContext(41L, 22L, 0L, "", 123L, "", 1L, "1021", "");

  @Test
  void resolveLookForwardsGameInstanceIdIntoRoomInstance() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.resolveLook(
            net.firedevops.firemud.gamelogic.v1.LookRequest.newBuilder()
                .setTenantId("22")
                .setSessionId("41")
                .setCharacterId("123")
                .setPreferredLocale("fr")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("22")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("1021")
                        .build())
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            LookResult.newBuilder()
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("22")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("1021")
                        .build())
                .build());
    setStub(client, stub);

    LookResult result = client.resolveLook(SESSION_CONTEXT, "1021", "fr");

    assertThat(result.getRoomInstance().getGameInstanceId()).isEqualTo("1");
  }

  @Test
  void resolveMoveForwardsGameInstanceIdIntoRoomInstance() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.resolveMove(
            net.firedevops.firemud.gamelogic.v1.MoveRequest.newBuilder()
                .setTenantId("22")
                .setSessionId("41")
                .setCharacterId("123")
                .setPreferredLocale("")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("22")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("1021")
                        .build())
                .setDirection("north")
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(MoveResult.newBuilder().setSuccess(true).build());
    setStub(client, stub);

    MoveResult result = client.resolveMove(SESSION_CONTEXT, "1021", "north", "");

    assertThat(result.getSuccess()).isTrue();
  }

  @Test
  void queryInventoryUsesGameLogicItemRuntimeRpc() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    QueryInventoryRequest request =
        QueryInventoryRequest.newBuilder()
            .setTenantId("22")
            .setCharacterId("123")
            .setSessionAttestation("attestation")
            .build();
    when(stub.queryInventory(request))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(InventoryItem.newBuilder().setItemId("7").setItemName("Torch").build())
                .build());
    setStub(client, stub);

    QueryInventoryResponse response = client.queryInventory(SESSION_CONTEXT);

    assertThat(response.getItemsList())
        .extracting(InventoryItem::getItemName)
        .containsExactly("Torch");
  }

  @Test
  void pickupItemForwardsRoomAndEffectContextThroughGameLogic() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    PickupItemFromRoomRequest request =
        PickupItemFromRoomRequest.newBuilder()
            .setTenantId("22")
            .setCharacterId("123")
            .setGameInstanceId("1")
            .setRoomInstanceId("1021")
            .setItemId("7")
            .setItemInstanceId("item-7")
            .setContainerInstanceId("ground-1021")
            .setStackFamilyKey("iron")
            .setQuantity(2)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(stub.pickupItemFromRoom(request))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setQuantity(2)
                        .build())
                .build());
    setStub(client, stub);

    PickupItemFromRoomResponse response =
        client.pickupItemFromRoom(
            SESSION_CONTEXT, "1021", "7", "item-7", "ground-1021", "iron", 2, "effect-1");

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Arrow");
  }

  private static GameLogicClient newClient() {
    GameplaySessionAttestationService attestationService =
        mock(GameplaySessionAttestationService.class);
    when(attestationService.issueGameplaySessionAttestation("22", "41", "0", "123", "1", "1021"))
        .thenReturn("attestation");
    return new GameLogicClient(
        new ServiceEndpointsProperties(),
        new CommonGrpcClientProperties(),
        mock(GrpcChannelFactory.class),
        BlockingGrpcStubCustomizer.noop(),
        attestationService);
  }

  private static void setStub(GameLogicClient client, Object stub) throws Exception {
    Field field =
        net.firedevops.firemud.common.grpc.AbstractBlockingGrpcClient.class.getDeclaredField(
            "stub");
    field.setAccessible(true);
    field.set(client, stub);
  }
}
