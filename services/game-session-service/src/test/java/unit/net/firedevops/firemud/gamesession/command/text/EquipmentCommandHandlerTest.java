package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import net.firedevops.firemud.entitymanagement.v1.EquipmentItem;
import net.firedevops.firemud.entitymanagement.v1.InventoryItem;
import net.firedevops.firemud.entitymanagement.v1.ListEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.QueryInventoryResponse;
import net.firedevops.firemud.entitymanagement.v1.RemoveEquipmentResponse;
import net.firedevops.firemud.entitymanagement.v1.WearEquipmentItemResponse;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EquipmentCommandHandlerTest {
  private final EntityManagementClient entityManagementClient =
      Mockito.mock(EntityManagementClient.class);
  private final EquipmentCommandHandler handler =
      new EquipmentCommandHandler(entityManagementClient);
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "room-7", "jwt-token");

  @Test
  void wearWithoutItemReferenceFailsFast() {
    TextCommandInterpretationResult result =
        handler.handle(context, new TextCommand(TextCommandType.WEAR, List.of(), "WEAR"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
    assertThat(result.outputs().get(0).text()).contains("WEAR command requires an item");
  }

  @Test
  void equipmentViewReturnsStructuredEquipmentLines() {
    when(entityManagementClient.listEquipment("22", "911"))
        .thenReturn(
            ListEquipmentResponse.newBuilder()
                .addItems(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("3")
                        .setItemName("Leather Cap")
                        .setItemDescription("A small cap")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(context, new TextCommand(TextCommandType.EQUIPMENT, List.of(), "EQUIPMENT"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.VIEW);
    assertThat(result.outputs().get(0).payload()).isInstanceOf(InventoryViewOutput.class);
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).title())
        .isEqualTo("Equipment:");
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).lines())
        .containsExactly("- HEAD: Leather Cap (A small cap)");
  }

  @Test
  void wearWithItemReferenceReturnsUnavailableResponse() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(QueryInventoryResponse.newBuilder().build());
    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.WEAR, List.of("Torch"), "WEAR Torch"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
    assertThat(result.outputs().get(0).text()).contains("No carried item matches");
  }

  @Test
  void wearWithItemReferenceCallsEquipmentBackend() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("3")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.wearEquipment("22", "911", "3"))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setEquipmentItem(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("3")
                        .setItemName("Torch")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.WEAR, List.of("Torch"), "WEAR Torch"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.MESSAGE);
    assertThat(result.outputs().get(0).text()).isEqualTo("You wear Torch.");
  }

  @Test
  void wearRejectsNonEmptyContainerUntilContainerInstancesExist() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("5")
                        .setItemName("Satchel")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.wearEquipment("22", "911", "5"))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setEquipmentItem(
                    EquipmentItem.newBuilder()
                        .setSlot("BACK")
                        .setItemId("5")
                        .setItemName("Satchel")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.WEAR, List.of("Satchel"), "WEAR Satchel"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.MESSAGE);
    assertThat(result.outputs().get(0).text()).isEqualTo("You wear Satchel.");
  }

  @Test
  void removeWithItemReferenceCallsEquipmentBackend() {
    when(entityManagementClient.listEquipment("22", "911"))
        .thenReturn(
            ListEquipmentResponse.newBuilder()
                .addItems(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("3")
                        .setItemName("Torch")
                        .build())
                .build());
    when(entityManagementClient.removeEquipment("22", "911", "HEAD"))
        .thenReturn(
            RemoveEquipmentResponse.newBuilder()
                .setEquipmentItem(
                    EquipmentItem.newBuilder()
                        .setSlot("HEAD")
                        .setItemId("3")
                        .setItemName("Torch")
                        .build())
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.REMOVE, List.of("Torch"), "REMOVE Torch"));

    assertThat(result.commandResult().accepted()).isTrue();
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.MESSAGE);
    assertThat(result.outputs().get(0).text()).isEqualTo("You remove Torch.");
  }

  @Test
  void wearPreservesDomainErrorCodeFromBackend() {
    when(entityManagementClient.queryInventory("22", "911"))
        .thenReturn(
            QueryInventoryResponse.newBuilder()
                .addItems(
                    InventoryItem.newBuilder()
                        .setItemId("3")
                        .setItemName("Torch")
                        .setQuantity(1)
                        .build())
                .build());
    when(entityManagementClient.wearEquipment("22", "911", "3"))
        .thenReturn(
            WearEquipmentItemResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder()
                        .setCode("SLOT_INCOMPATIBLE")
                        .setMessage("Torch cannot be worn there"))
                .build());

    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.WEAR, List.of("Torch"), "WEAR Torch"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("SLOT_INCOMPATIBLE");
    assertThat(result.outputs().get(0).text()).contains("Torch cannot be worn there");
  }
}
