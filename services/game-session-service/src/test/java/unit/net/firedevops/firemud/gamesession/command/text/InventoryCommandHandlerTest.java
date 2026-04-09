package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.EntityType;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListRoomEntitiesResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomEntity;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InventoryCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final InventoryCommandHandler handler =
      new InventoryCommandHandler(entityManagementClient);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "room-7", "jwt-token");

  @Test
  void inventoryReturnsStructuredViewFromRuntimeContract() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(context, new TextCommand(TextCommandType.INVENTORY, List.of(), "INVENTORY"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.VIEW);
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).lines())
        .containsExactly("- Torch x2 (A small torch)");
  }

  @Test
  void inventoryHereReturnsRoomGroundItemsWithVisibleRefs() {
    when(entityManagementClient.listRoomEntities("22", "77", "room-7"))
        .thenReturn(
            ListRoomEntitiesResponse.newBuilder()
                .addEntities(
                    RoomEntity.newBuilder()
                        .setEntityId("22:77:room-7:1001")
                        .setEntityType(EntityType.ITEM)
                        .setDisplayName("Torch")
                        .setVisibleRef("torch3")
                        .addStateFlags("room-ground")
                        .build())
                .addEntities(
                    RoomEntity.newBuilder()
                        .setEntityId("22:77:room-7:npc-1")
                        .setEntityType(EntityType.NPC)
                        .setDisplayName("Goblin")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.INVENTORY, List.of("HERE"), "INV HERE"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.VIEW);
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(0).payload();
    assertThat(view.title()).isEqualTo("Room Inventory:");
    assertThat(view.lines()).containsExactly("- Torch [torch3]");
  }

  @Test
  void inventoryHereReturnsEmptyStateWhenNoRoomGroundItemsExist() {
    when(entityManagementClient.listRoomEntities("22", "77", "room-7"))
        .thenReturn(ListRoomEntitiesResponse.newBuilder().build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.INVENTORY, List.of("HERE"), "INV HERE"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).lines())
        .containsExactly("There is nothing on the ground here.");
  }

  @Test
  void getWithoutItemReferenceFailsFast() {
    InventoryCommandHandlingResult result =
        handler.handle(context, new TextCommand(TextCommandType.GET, List.of(), "GET"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
  }

  @Test
  void getWithItemReferenceCallsPickupMutation() {
    when(entityManagementClient.listRoomEntities("22", "77", "room-7"))
        .thenReturn(
            ListRoomEntitiesResponse.newBuilder()
                .addEntities(
                    RoomEntity.newBuilder()
                        .setEntityId("22:77:room-7:7")
                        .setDisplayName("Rough Iron Key")
                        .setEntityType(EntityType.ITEM)
                        .addStateFlags("room-ground")
                        .build())
                .build());
    when(entityManagementClient.pickupItemFromRoom("22", "911", "77", "room-7", "7", "7", "7", 1))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Rough Iron Key")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Rough Iron Key")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.GET, List.of("rough iron key"), "GET rough iron key"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You pick up Rough Iron Key.");
    assertThat(((InventoryViewOutput) result.outputs().get(1).payload()).lines())
        .containsExactly("- Rough Iron Key (A battered key)");
  }

  @Test
  void getWithQuantityCallsPickupMutation() {
    when(entityManagementClient.listRoomEntities("22", "77", "room-7"))
        .thenReturn(
            ListRoomEntitiesResponse.newBuilder()
                .addEntities(
                    RoomEntity.newBuilder()
                        .setEntityId("22:77:room-7:7")
                        .setDisplayName("Torch")
                        .setEntityType(EntityType.ITEM)
                        .addStateFlags("room-ground")
                        .build())
                .build());
    when(entityManagementClient.pickupItemFromRoom("22", "911", "77", "room-7", "7", "7", "7", 2))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.GET, List.of("2", "Torch"), "GET 2 Torch"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You pick up Torch x2.");
    assertThat(((InventoryViewOutput) result.outputs().get(1).payload()).lines())
        .containsExactly("- Torch x2 (A small torch)");
  }

  @Test
  void getMatchesExplicitRoomItemReference() {
    when(entityManagementClient.listRoomEntities("22", "77", "room-7"))
        .thenReturn(
            ListRoomEntitiesResponse.newBuilder()
                .addEntities(
                    RoomEntity.newBuilder()
                        .setEntityId("22:77:room-7:ITEM-009")
                        .setDisplayName("Torch")
                        .setEntityType(EntityType.ITEM)
                        .addStateFlags("room-ground")
                        .build())
                .build());
    when(entityManagementClient.pickupItemFromRoom(
            "22", "911", "77", "room-7", "ITEM-009", "ITEM-009", "ITEM-009", 1))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemInstanceId("ITEM-009")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.GET, List.of("ITEM-009"), "GET ITEM-009"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs().get(0).text()).isEqualTo("You pick up Torch.");
  }

  @Test
  void dropWithItemReferenceCallsDropMutation() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("7")
                        .setItemName("Rough Iron Key")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build(),
            QueryInventoryResponse.newBuilder().build());
    when(entityManagementClient.dropItemToRoom("22", "911", "77", "room-7", "7", "7", "7", 1))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Rough Iron Key")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.DROP, List.of("rough iron key"), "DROP rough iron key"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You drop Rough Iron Key.");
    assertThat(((InventoryViewOutput) result.outputs().get(1).payload()).lines())
        .containsExactly("You are not carrying anything.");
  }

  @Test
  void dropWithQuantityCallsDropMutation() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(3)
                        .build())
                .build(),
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.dropItemToRoom("22", "911", "77", "room-7", "7", "7", "7", 2))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.DROP, List.of("2", "Torch"), "DROP 2 Torch"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You drop Torch x2.");
    assertThat(((InventoryViewOutput) result.outputs().get(1).payload()).lines())
        .containsExactly("- Torch (A small torch)");
  }

  @Test
  void dropAllowsNonEmptyCarriedContainerWhenBackendPreservesIdentity() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemInstanceId("10")
                        .setItemName("Old Chest")
                        .setItemDescription("A worn chest")
                        .setContainerInstanceId("container-10")
                        .setQuantity(1)
                        .build())
                .build())
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.dropItemToRoom(
            "22", "911", "77", "room-7", "10", "10", "container-10", 1))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setQuantity(1)
                        .setContainerInstanceId("container-10")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.DROP, List.of("Old Chest"), "DROP Old Chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You drop Old Chest.");
  }

  @Test
  void dropWithItemReferenceReportsRuntimeError() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("7")
                        .setItemName("Rough Iron Key")
                        .setItemDescription("A battered key")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.dropItemToRoom("22", "911", "77", "room-7", "7", "7", "7", 1))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("Item not found for tenant")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.DROP, List.of("rough iron key"), "DROP rough iron key"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
    assertThat(result.outputs().get(0).text())
        .isEqualTo("ERROR INVALID_ARGUMENT Item not found for tenant");
  }
}
