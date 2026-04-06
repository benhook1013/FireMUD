package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.ContainerItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListContainerContentsResponse;
import net.firedevops.firemud.entitymanagement.v1.PutItemIntoContainerResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.TakeItemFromContainerResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ContainerCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final ContainerCommandHandler handler =
      new ContainerCommandHandler(entityManagementClient);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "room-7", "jwt-token");

  @Test
  void containerViewReturnsStructuredContents() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setContainerInstanceId("container-10")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.listContainerContents("22", "911", "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
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
    assertThat(view.title()).isEqualTo("Container: Old Chest");
    assertThat(view.lines()).containsExactly("- Torch x2 (A small torch)");
  }

  @Test
  void putMovesItemIntoContainerAndRefreshesView() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setContainerInstanceId("container-10")
                        .build())
                .addItems(InventoryItem.newBuilder().setItemId("99").setItemName("Torch").build())
                .build());
    when(entityManagementClient.putItemIntoContainer("22", "911", "container-10", "99", 1))
        .thenReturn(
            PutItemIntoContainerResponse.newBuilder()
                .setContainerItem(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.listContainerContents("22", "911", "container-10"))
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
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.MESSAGE);
    assertThat(result.outputs().get(0).text()).isEqualTo("You put Torch into Old Chest.");
    assertThat(result.outputs().get(1).kind()).isEqualTo(PlayerOutputKind.VIEW);
  }

  @Test
  void takeMovesItemOutOfContainerAndRefreshesView() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("10")
                        .setItemName("Old Chest")
                        .setContainerInstanceId("container-10")
                        .build())
                .build());
    when(entityManagementClient.listContainerContents("22", "911", "container-10"))
        .thenReturn(
            ListContainerContentsResponse.newBuilder()
                .addItems(
                    ContainerItem.newBuilder()
                        .setContainerInstanceId("container-10")
                        .setItemId("99")
                        .setItemName("Torch")
                        .setQuantity(2)
                        .build())
                .build(),
            ListContainerContentsResponse.newBuilder().build());
    when(entityManagementClient.takeItemFromContainer("22", "911", "container-10", "99", 2))
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
    assertThat(result.outputs().get(0).text()).isEqualTo("You take Torch x2 from Old Chest.");
    InventoryViewOutput view = (InventoryViewOutput) result.outputs().get(1).payload();
    assertThat(view.lines()).containsExactly("It is empty.");
    verify(entityManagementClient).takeItemFromContainer("22", "911", "container-10", "99", 2);
  }
}
