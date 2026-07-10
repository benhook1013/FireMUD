package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.DropItemToRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PickupItemFromRoomResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InventoryCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final InventoryCommandHandler handler = new InventoryCommandHandler(gameLogicClient);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "R-7", "jwt-token");

  @Test
  void inventoryReturnsStructuredViewFromRuntimeContract() {
    when(gameLogicClient.queryInventory(context))
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
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(0).payload();
    assertThat(view.source()).isEqualTo(InventoryViewOutput.Source.INVENTORY);
    assertThat(view.lines()).containsExactly("- Torch x2 (A small torch)");
  }

  @Test
  void inventoryShowsDuplicateNonStackableItemsAsSeparateEntries() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("101")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .setVisibleRef("torch1")
                        .build())
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("102")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setQuantity(1)
                        .setVisibleRef("torch2")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(context, new TextCommand(TextCommandType.INVENTORY, List.of(), "INVENTORY"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).lines())
        .containsExactly("- Torch [torch1] (A small torch)", "- Torch [torch2] (A small torch)");
  }

  @Test
  void inventoryHereReturnsRoomGroundItemsWithVisibleRefs() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("1001")
                        .setItemName("Torch")
                        .setItemDescription("A small torch")
                        .setVisibleRef("torch3")
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
    assertThat(view.source()).isEqualTo(InventoryViewOutput.Source.ROOM_GROUND);
    assertThat(view.title()).isEqualTo("Room Inventory:");
    assertThat(view.lines()).containsExactly("- Torch [torch3] (A small torch)");
  }

  @Test
  void inventoryHereReturnsStackedRoomGroundItemsWithStackRefs() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("1001")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(12)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.INVENTORY, List.of("HERE"), "INV HERE"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(0).payload();
    assertThat(view.source()).isEqualTo(InventoryViewOutput.Source.ROOM_GROUND);
    assertThat(view.title()).isEqualTo("Room Inventory:");
    assertThat(view.lines()).containsExactly("- Arrow [ammo/iron] x12 (A straight wooden arrow)");
  }

  @Test
  void inventoryHereReturnsEmptyStateWhenNoRoomGroundItemsExist() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(ListRoomGroundInventoryResponse.newBuilder().build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.INVENTORY, List.of("HERE"), "INV HERE"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).source())
        .isEqualTo(InventoryViewOutput.Source.ROOM_GROUND);
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
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("7")
                        .setItemName("Rough Iron Key")
                        .build())
                .build());
    when(gameLogicClient.pickupVisibleRoomItem(context, "rough iron key", 1))
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
    when(gameLogicClient.queryInventory(context))
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
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("7")
                        .setVisibleRef("torch1")
                        .setItemName("Torch")
                        .build())
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemInstanceId("8")
                        .setVisibleRef("torch2")
                        .setItemName("Torch")
                        .build())
                .build());
    when(gameLogicClient.pickupVisibleRoomItem(context, "Torch", 2))
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
    when(gameLogicClient.queryInventory(context))
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
  void getWithQuantityMatchesStackedRoomGroundEntryWithoutExplicitRef() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(12)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());
    when(gameLogicClient.pickupVisibleRoomItem(context, "Arrow", 3))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(3)
                        .build())
                .build());
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(3)
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.GET, List.of("3", "Arrow"), "GET 3 Arrow"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.MESSAGE, PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).text()).isEqualTo("You pick up Arrow x3.");
    assertThat(((InventoryViewOutput) result.outputs().get(1).payload()).lines())
        .containsExactly("- Arrow x3 (A straight wooden arrow)");
  }

  @Test
  void getWithExplicitStackReferenceAllowsQuantitySelection() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(12)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());
    when(gameLogicClient.pickupVisibleRoomItem(context, "ammo/iron", 3))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(3)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(3)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.GET, List.of("3", "ammo/iron"), "GET 3 ammo/iron"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs().get(0).text()).isEqualTo("You pick up Arrow x3.");
  }

  @Test
  void getWithExplicitReferenceRejectsQuantityGreaterThanOne() {
    when(gameLogicClient.pickupVisibleRoomItem(context, "torch1", 2))
        .thenReturn(
            PickupItemFromRoomResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("Explicit item refs require quantity 1 for GET"))
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.GET, List.of("2", "torch1"), "GET 2 torch1"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(out -> assertThat(out.text()).contains("quantity 1"));
  }

  @Test
  void getMatchesExplicitRoomItemReference() {
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("ITEM-009")
                        .setItemInstanceId("ITEM-009")
                        .setItemName("Torch")
                        .build())
                .build());
    when(gameLogicClient.pickupVisibleRoomItem(context, "ITEM-009", 1))
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
    when(gameLogicClient.queryInventory(context))
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
    when(gameLogicClient.queryInventory(context))
        .thenReturn(QueryInventoryResponse.newBuilder().build());
    when(gameLogicClient.dropCarriedItem(context, "rough iron key", 1))
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
    when(gameLogicClient.queryInventory(context))
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
    when(gameLogicClient.dropCarriedItem(context, "Torch", 2))
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
  void dropWithExplicitReferenceRejectsQuantityGreaterThanOne() {
    when(gameLogicClient.dropCarriedItem(context, "torch1", 2))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("INVALID_ARGUMENT")
                        .setMessage("Explicit item refs require quantity 1 for DROP"))
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.DROP, List.of("2", "torch1"), "DROP 2 torch1"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(out -> assertThat(out.text()).contains("quantity 1"));
  }

  @Test
  void dropWithExplicitStackReferenceAllowsQuantitySelection() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(8)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build(),
            QueryInventoryResponse.newBuilder().build());
    when(gameLogicClient.dropCarriedItem(context, "ammo/iron", 3))
        .thenReturn(
            DropItemToRoomResponse.newBuilder()
                .setRoomGroundItem(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("7")
                        .setItemName("Arrow")
                        .setItemDescription("A straight wooden arrow")
                        .setQuantity(3)
                        .setVisibleRef("ammo/iron")
                        .build())
                .build());

    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.DROP, List.of("3", "ammo/iron"), "DROP 3 ammo/iron"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs().get(0).text()).isEqualTo("You drop Arrow x3.");
  }

  @Test
  void dropAllowsNonEmptyCarriedContainerWhenBackendPreservesIdentity() {
    when(gameLogicClient.queryInventory(context))
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
    when(gameLogicClient.dropCarriedItem(context, "Old Chest", 1))
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
    when(gameLogicClient.queryInventory(context))
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
    when(gameLogicClient.dropCarriedItem(context, "rough iron key", 1))
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
