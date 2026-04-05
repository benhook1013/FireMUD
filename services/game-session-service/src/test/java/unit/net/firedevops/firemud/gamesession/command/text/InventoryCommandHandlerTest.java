package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class InventoryCommandHandlerTest {
  private final InventoryCommandHandler handler = new InventoryCommandHandler();
  private final SessionContext context =
      new SessionContext(
          1L, 22L, 123L, "emberline@example.com", 911L, "Emberline", 77L, "room-7", "jwt-token");

  @Test
  void inventoryReturnsStructuredPlaceholderView() {
    InventoryCommandHandlingResult result =
        handler.handle(context, new TextCommand(TextCommandType.INVENTORY, List.of(), "INVENTORY"));

    assertThat(result.commandResult()).isEqualTo(CommandEnqueueResult.success());
    assertThat(result.outputs())
        .extracting(PlayerOutput::kind)
        .containsExactly(PlayerOutputKind.VIEW);
    assertThat(((InventoryViewOutput) result.outputs().get(0).payload()).lines())
        .containsExactly(
            "This command surface is ready.",
            "The runtime inventory contract is still being wired.");
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
  void dropWithItemReferenceUsesPendingRuntimeContractError() {
    InventoryCommandHandlingResult result =
        handler.handle(
            context,
            new TextCommand(
                TextCommandType.DROP, List.of("rough iron key"), "DROP rough iron key"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVENTORY_UNAVAILABLE");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).text())
        .isEqualTo(
            "ERROR INVENTORY_UNAVAILABLE DROP rough iron key is not yet wired to runtime inventory state");
  }
}
