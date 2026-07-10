package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.ListRoomGroundInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RoomGroundInventoryItem;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.ItemMutationResultOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ContainerCommandHandlerTest {
  private final GameLogicClient gameLogicClient = Mockito.mock(GameLogicClient.class);
  private final ContainerCommandHandler handler = new ContainerCommandHandler(gameLogicClient);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "R-7", "jwt-token");

  @Test
  void containerViewReturnsStructuredContents() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .setContainerInstanceId("container-10")
                        .setQuantity(1)
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .setItemDescription("A small torch")
                        .setQuantity(2)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.CONTAINER, List.of("old", "chest"), "CONTAINER old chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).payload()).isInstanceOf(InventoryViewOutput.class);
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(0).payload();
    assertThat(view.source()).isEqualTo(InventoryViewOutput.Source.CONTAINER);
    assertThat(view.title()).isEqualTo("Container: Old Chest [oldchest10]");
    assertThat(view.lines()).containsExactly("- Torch [torch3] x2 (A small torch)");
    assertThat(view.context())
        .isEqualTo(new InventoryViewOutput.ViewContext("container-10", "Old Chest", "oldchest10"));
    assertThat(view.entries())
        .containsExactly(
            new InventoryViewOutput.ItemEntry(
                "99", "", "container-10", "torch3", "Torch", "A small torch", 2, ""));
  }

  @Test
  void putMovesItemIntoContainerAndRefreshesView() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .setContainerInstanceId("container-10")
                        .build())
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .build())
                .build());
    when(gameLogicClient.putItemIntoContainer(context, "container-10", "99", null, null, 1))
        .thenReturn(
            PutItemIntoContainerResponse.newBuilder()
                .setContainerItem(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .setQuantity(1)
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.PUT,
                List.of("torch", "INTO", "old", "chest"),
                "PUT torch INTO old chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(2);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("PUT");
    assertThat(mutation.item())
        .isEqualTo(
            new InventoryViewOutput.ItemEntry(
                "99", "", "container-10", "torch3", "Torch", "", 1, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Old Chest",
                "container-10",
                "oldchest10",
                ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You put Torch into Old Chest.");
    assertThat(result.outputs().get(1).kind()).isEqualTo(PlayerOutputKind.VIEW);
  }

  @Test
  void putIntoContainerRejectsExplicitRefWithQuantityGreaterThanOne() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .setContainerInstanceId("container-10")
                        .build())
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemInstanceId("44")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .setQuantity(2)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.PUT,
                List.of("2", "torch3", "INTO", "old", "chest"),
                "PUT 2 torch3 INTO old chest"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(output -> assertThat(output.text()).contains("quantity 1"));
  }

  @Test
  void takeMovesItemOutOfContainerAndRefreshesView() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .setContainerInstanceId("container-10")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .setQuantity(2)
                        .build())
                .build(),
            ListContainerContentsResponse.newBuilder().build());
    when(gameLogicClient.takeItemFromContainer(context, "container-10", "99", null, null, 2))
        .thenReturn(
            TakeItemFromContainerResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Torch")
                        .setQuantity(2)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.TAKE,
                List.of("2", "torch", "FROM", "old", "chest"),
                "TAKE 2 torch FROM old chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(2);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("TAKE");
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    assertThat(mutation.item())
        .isEqualTo(new InventoryViewOutput.ItemEntry("99", "", "", "", "Torch", "", 2, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Old Chest",
                "container-10",
                "oldchest10",
                ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You take Torch x2 from Old Chest.");
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(1).payload();
    assertThat(view.lines()).containsExactly("It is empty.");
    verify(gameLogicClient).takeItemFromContainer(context, "container-10", "99", null, null, 2);
  }

  @Test
  void takeFromContainerRejectsExplicitRefWithQuantityGreaterThanOne() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("50")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemInstanceId("44")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .setQuantity(2)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.TAKE,
                List.of("2", "torch3", "FROM", "old", "chest"),
                "TAKE 2 torch3 FROM old chest"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.outputs())
        .singleElement()
        .satisfies(output -> assertThat(output.text()).contains("quantity 1"));
  }

  @Test
  void putIntoContainerAllowsExplicitStackRefWithQuantitySelection() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .setContainerInstanceId("container-10")
                        .build())
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Arrow")
                        .setVisibleRef("ammo/iron")
                        .setQuantity(12)
                        .build())
                .build());
    when(gameLogicClient.putItemIntoContainer(context, "container-10", "99", null, "ammo/iron", 3))
        .thenReturn(
            PutItemIntoContainerResponse.newBuilder()
                .setContainerItem(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Arrow")
                        .setVisibleRef("ammo/iron")
                        .setQuantity(3)
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(ListContainerContentsResponse.newBuilder().build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.PUT,
                List.of("3", "ammo/iron", "INTO", "old", "chest"),
                "PUT 3 ammo/iron INTO old chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("PUT");
    assertThat(mutation.item())
        .isEqualTo(
            new InventoryViewOutput.ItemEntry(
                "99", "", "container-10", "ammo/iron", "Arrow", "", 3, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Old Chest",
                "container-10",
                "oldchest10",
                ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You put Arrow x3 into Old Chest.");
  }

  @Test
  void takeFromContainerAllowsExplicitStackRefWithQuantitySelection() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("50")
                        .setItemName("Old Chest")
                        .setVisibleRef("oldchest10")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Arrow")
                        .setVisibleRef("ammo/iron")
                        .setQuantity(12)
                        .build())
                .build(),
            ListContainerContentsResponse.newBuilder().build());
    when(gameLogicClient.takeItemFromContainer(context, "container-10", "99", null, "ammo/iron", 3))
        .thenReturn(
            TakeItemFromContainerResponse.newBuilder()
                .setInventoryItem(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Arrow")
                        .setVisibleRef("ammo/iron")
                        .setQuantity(3)
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.TAKE,
                List.of("3", "ammo/iron", "FROM", "old", "chest"),
                "TAKE 3 ammo/iron FROM old chest"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("TAKE");
    assertThat(mutation.item())
        .isEqualTo(
            new InventoryViewOutput.ItemEntry("99", "", "", "ammo/iron", "Arrow", "", 3, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Old Chest",
                "container-10",
                "oldchest10",
                ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You take Arrow x3 from Old Chest.");
  }

  @Test
  void containerViewResolvesRoomGroundContainer() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(QueryInventoryResponse.newBuilder().build());
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Dropped Chest")
                        .setVisibleRef("chest#1")
                        .setContainerInstanceId("container-10")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(ListContainerContentsResponse.newBuilder().build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(TextCommandType.CONTAINER, List.of("chest#1"), "CONTAINER chest#1"));

    assertThat(result.commandResult().accepted()).isTrue();
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(0).payload();
    assertThat(view.source()).isEqualTo(InventoryViewOutput.Source.CONTAINER);
    assertThat(view.title()).isEqualTo("Container: Dropped Chest [chest#1]");
    assertThat(view.lines()).containsExactly("It is empty.");
    assertThat(view.context())
        .isEqualTo(new InventoryViewOutput.ViewContext("container-10", "Dropped Chest", "chest#1"));
    assertThat(view.entries()).isEmpty();
  }

  @Test
  void putIntoRoomGroundContainerUsesRoomResolution() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("99")
                        .setItemName("Torch")
                        .setVisibleRef("torch3")
                        .build())
                .build());
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Dropped Chest")
                        .setVisibleRef("chest#1")
                        .setContainerInstanceId("container-10")
                        .build())
                .build());
    when(gameLogicClient.putItemIntoContainer(context, "container-10", "99", null, null, 1))
        .thenReturn(PutItemIntoContainerResponse.newBuilder().build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(ListContainerContentsResponse.newBuilder().build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.PUT,
                List.of("torch", "INTO", "chest#1"),
                "PUT torch INTO chest#1"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("PUT");
    assertThat(mutation.item())
        .isEqualTo(new InventoryViewOutput.ItemEntry("", "", "", "", "Torch", "", 1, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Dropped Chest",
                "container-10",
                "chest#1",
                ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You put Torch into Dropped Chest.");
  }

  @Test
  void takeFromRoomGroundContainerUsesRoomResolution() {
    when(gameLogicClient.queryInventory(context))
        .thenReturn(QueryInventoryResponse.newBuilder().build());
    when(gameLogicClient.listRoomGroundInventory(context, "R-7"))
        .thenReturn(
            ListRoomGroundInventoryResponse.newBuilder()
                .addItems(
                    RoomGroundInventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Dropped Chest")
                        .setVisibleRef("chest#1")
                        .setContainerInstanceId("container-10")
                        .build())
                .build());
    when(gameLogicClient.listContainerContents(context, "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .build())
                .build(),
            ListContainerContentsResponse.newBuilder().build());
    when(gameLogicClient.takeItemFromContainer(context, "container-10", "99", null, null, 1))
        .thenReturn(TakeItemFromContainerResponse.newBuilder().build());

    TextCommandInterpretationResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.TAKE,
                List.of("torch", "FROM", "chest#1"),
                "TAKE torch FROM chest#1"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.NOTICE);
    ItemMutationResultOutput mutation =
        (ItemMutationResultOutput) result.outputs().get(0).payload();
    assertThat(mutation.action()).isEqualTo("TAKE");
    assertThat(mutation.item())
        .isEqualTo(new InventoryViewOutput.ItemEntry("", "", "", "", "Torch", "", 1, ""));
    assertThat(mutation.source())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.CONTAINER,
                "Dropped Chest",
                "container-10",
                "chest#1",
                ""));
    assertThat(mutation.target())
        .isEqualTo(
            new ItemMutationResultOutput.HolderContext(
                InventoryViewOutput.Source.INVENTORY, "", "", "", ""));
    assertThat(result.outputs().get(0).text()).isEqualTo("You take Torch from Dropped Chest.");
  }
}
