package net.firedevops.firemud.gamesession.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.config.ServiceEndpointsProperties;
import net.firedevops.firemud.common.grpc.BlockingGrpcStubCustomizer;
import net.firedevops.firemud.common.grpc.CommonGrpcClientProperties;
import net.firedevops.firemud.common.grpc.GrpcChannelFactory;
import net.firedevops.firemud.common.security.GameplaySessionAttestationService;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomRequest;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryRequest;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamelogic.v1.DropCarriedItemRequest;
import net.firedevops.firemud.gamelogic.v1.GameLogicServiceGrpc;
import net.firedevops.firemud.gamelogic.v1.LookRequest;
import net.firedevops.firemud.gamelogic.v1.LookResult;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamelogic.v1.PickupVisibleRoomItemRequest;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.RoomInstanceRef;
import org.junit.jupiter.api.Test;

class GameLogicClientTest {
  private static final SessionContext SESSION_CONTEXT =
      new SessionContext(
          41L, 22L, 0L, "", 123L, "", 1L, "R-1021", "", null, 1L, "world", "realm", 17L, "SHARED");

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
                        .setRoomInstanceId("R-1021")
                        .build())
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(
            LookResult.newBuilder()
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("22")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("R-1021")
                        .build())
                .build());
    setStub(client, stub);

    LookResult result = client.resolveLook(SESSION_CONTEXT, "R-1021", "fr");

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
                        .setRoomInstanceId("R-1021")
                        .build())
                .setDirection("north")
                .setSessionAttestation("attestation")
                .build()))
        .thenReturn(MoveResult.newBuilder().setSuccess(true).build());
    setStub(client, stub);

    MoveResult result = client.resolveMove(SESSION_CONTEXT, "R-1021", "north", "");

    assertThat(result.getSuccess()).isTrue();
  }

  @Test
  void resolveLookForReadinessUsesInternalProbeAttestation() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(2L, TimeUnit.SECONDS)).thenReturn(stub);
    when(stub.resolveLook(
            LookRequest.newBuilder()
                .setTenantId("22")
                .setSessionId("41")
                .setCharacterId("123")
                .setRoomInstance(
                    RoomInstanceRef.newBuilder()
                        .setTenantId("22")
                        .setGameInstanceId("1")
                        .setRoomInstanceId("R-1021")
                        .build())
                .setSessionAttestation("probe-attestation")
                .build()))
        .thenReturn(LookResult.newBuilder().build());
    setStub(client, stub);

    client.resolveLookForReadiness("22", "41", "123", "1", "R-1021");

    assertThat(true).isTrue();
  }

  @Test
  void resolveLookRejectsLegacyRuntimeRoomIdsBeforeDispatch() {
    GameLogicClient client = newClient();

    assertThatThrownBy(() -> client.resolveLook(SESSION_CONTEXT, "room-1021", "fr"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomInstanceId must be a runtime room id like R-1021");
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
            .setGameInstanceId("1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
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
  void queryInventoryRejectsLegacyRuntimeRoomIdsInSessionContextBeforeDispatch() {
    GameLogicClient client = newClient();
    SessionContext legacyRoomContext =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            "room-1021",
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            SESSION_CONTEXT.worldSlug(),
            SESSION_CONTEXT.realmSlug(),
            SESSION_CONTEXT.pointerVersion(),
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(() -> client.queryInventory(legacyRoomContext))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("roomInstanceId must be a runtime room id like R-1021");
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
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("R-1021")
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
            SESSION_CONTEXT, "R-1021", "7", "item-7", "ground-1021", "iron", 2, "effect-1");

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Arrow");
  }

  @Test
  void pickupVisibleRoomItemForwardsRawReferenceAndSessionContextThroughGameLogic()
      throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    PickupVisibleRoomItemRequest request =
        PickupVisibleRoomItemRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("41")
            .setAccountId("0")
            .setCharacterId("123")
            .setGameInstanceId("1")
            .setRoomInstanceId("R-1021")
            .setItemReference("torch1")
            .setQuantity(1)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(stub.pickupVisibleRoomItem(request))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(InventoryItem.newBuilder().setItemName("Torch"))
                .build());
    setStub(client, stub);

    PickupItemFromRoomResponse response =
        client.pickupVisibleRoomItem(SESSION_CONTEXT, "torch1", 1, "effect-1");

    assertThat(response.getInventoryItem().getItemName()).isEqualTo("Torch");
  }

  @Test
  void listRoomGroundInventoryForwardsCurrentGameRoomAndAttestation() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    ListRoomGroundInventoryRequest request =
        ListRoomGroundInventoryRequest.newBuilder()
            .setTenantId("22")
            .setGameInstanceId("1")
            .setRoomInstanceId("R-1021")
            .setSessionAttestation("attestation")
            .build();
    when(stub.listRoomGroundInventory(request))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .build())
                .build());
    setStub(client, stub);

    ListRoomGroundInventoryResponse response =
        client.listRoomGroundInventory(SESSION_CONTEXT, "R-1021");

    assertThat(response.getItemsList())
        .extracting(RoomGroundInventoryItem::getItemName)
        .containsExactly("Torch");
  }

  @Test
  void dropItemForwardsRoomContainerStackAndEffectContextThroughGameLogic() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    DropItemToRoomRequest request =
        DropItemToRoomRequest.newBuilder()
            .setTenantId("22")
            .setCharacterId("123")
            .setGameInstanceId("1")
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setRoomInstanceId("R-1021")
            .setItemId("7")
            .setItemInstanceId("item-7")
            .setContainerInstanceId("container-7")
            .setStackFamilyKey("iron")
            .setQuantity(2)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(stub.dropItemToRoom(request))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setQuantity(2)
                        .build())
                .build());
    setStub(client, stub);

    DropItemToRoomResponse response =
        client.dropItemToRoom(
            SESSION_CONTEXT, "R-1021", "7", "item-7", "container-7", "iron", 2, "effect-1");

    assertThat(response.getRoomGroundItem().getItemName()).isEqualTo("Arrow");
  }

  @Test
  void dropCarriedItemForwardsRawReferenceAndSessionContextThroughGameLogic() throws Exception {
    GameLogicClient client = newClient();
    GameLogicServiceGrpc.GameLogicServiceBlockingStub stub =
        mock(GameLogicServiceGrpc.GameLogicServiceBlockingStub.class);
    when(stub.withDeadlineAfter(5L, TimeUnit.SECONDS)).thenReturn(stub);
    DropCarriedItemRequest request =
        DropCarriedItemRequest.newBuilder()
            .setTenantId("22")
            .setSessionId("41")
            .setAccountId("0")
            .setCharacterId("123")
            .setGameInstanceId("1")
            .setRoomInstanceId("R-1021")
            .setItemReference("torch1")
            .setQuantity(1)
            .setPlayableStateScope(PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED)
            .setSessionAttestation("attestation")
            .setEffectId("effect-1")
            .build();
    when(stub.dropCarriedItem(request))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(RoomGroundInventoryItem.newBuilder().setItemName("Torch"))
                .build());
    setStub(client, stub);

    DropItemToRoomResponse response =
        client.dropCarriedItem(SESSION_CONTEXT, "torch1", 1, "effect-1");

    assertThat(response.getRoomGroundItem().getItemName()).isEqualTo("Torch");
  }

  @Test
  void queryInventoryFailsClosedWhenSessionContextDropsAdmittedPlayableStateScope() {
    GameLogicClient client = newClient();
    SessionContext missingScope =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            SESSION_CONTEXT.roomInstanceId(),
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            SESSION_CONTEXT.worldSlug(),
            SESSION_CONTEXT.realmSlug(),
            SESSION_CONTEXT.pointerVersion(),
            null);

    assertThatThrownBy(() -> client.queryInventory(missingScope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing admitted playableStateScope");
  }

  @Test
  void queryInventoryFailsClosedWhenSessionContextDropsPartOfAdmittedRoutingBundle() {
    GameLogicClient client = newClient();
    SessionContext partialRouting =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            SESSION_CONTEXT.roomInstanceId(),
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            SESSION_CONTEXT.worldSlug(),
            SESSION_CONTEXT.realmSlug(),
            0L,
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(() -> client.queryInventory(partialRouting))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Incomplete admitted routing bundle");
  }

  @Test
  void queryInventoryFailsClosedWhenSessionContextDropsEntireAdmittedRoutingBundle() {
    GameLogicClient client = newClient();
    SessionContext missingRouting =
        new SessionContext(
            SESSION_CONTEXT.sessionId(),
            SESSION_CONTEXT.tenantId(),
            SESSION_CONTEXT.accountId(),
            SESSION_CONTEXT.loginName(),
            SESSION_CONTEXT.characterId(),
            SESSION_CONTEXT.characterName(),
            SESSION_CONTEXT.gameInstanceId(),
            SESSION_CONTEXT.roomInstanceId(),
            SESSION_CONTEXT.jwt(),
            SESSION_CONTEXT.localeTag(),
            SESSION_CONTEXT.bootstrapGameInstanceId(),
            null,
            null,
            0L,
            SESSION_CONTEXT.playableStateScope());

    assertThatThrownBy(() -> client.queryInventory(missingRouting))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Missing admitted routing bundle");
  }

  private static GameLogicClient newClient() {
    GameplaySessionAttestationService attestationService =
        mock(GameplaySessionAttestationService.class);
    when(attestationService.issueGameplaySessionAttestation(
            "22", "41", "0", "123", "1", "R-1021", "world", "realm", "17", "SHARED"))
        .thenReturn("attestation");
    when(attestationService.issueInternalProbeAttestation("22", "1", "R-1021"))
        .thenReturn("probe-attestation");
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
