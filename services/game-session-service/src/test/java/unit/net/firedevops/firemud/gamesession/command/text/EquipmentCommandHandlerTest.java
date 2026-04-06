package net.firedevops.firemud.gamesession.command.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.junit.jupiter.api.Test;

class EquipmentCommandHandlerTest {
  private final EquipmentCommandHandler handler = new EquipmentCommandHandler();
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
  void wearWithItemReferenceReturnsUnavailableResponse() {
    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.WEAR, List.of("Torch"), "WEAR Torch"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("EQUIPMENT_UNAVAILABLE");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
    assertThat(result.outputs().get(0).text())
        .isEqualTo(
            "ERROR EQUIPMENT_UNAVAILABLE WEAR Torch is prepared in the command surface, but the equipment runtime is not yet wired.");
  }

  @Test
  void removeWithItemReferenceReturnsUnavailableResponse() {
    TextCommandInterpretationResult result =
        handler.handle(
            context, new TextCommand(TextCommandType.REMOVE, List.of("Torch"), "REMOVE Torch"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("EQUIPMENT_UNAVAILABLE");
    assertThat(result.outputs()).hasSize(1);
    assertThat(result.outputs().get(0).kind()).isEqualTo(PlayerOutputKind.ERROR);
    assertThat(result.outputs().get(0).text())
        .isEqualTo(
            "ERROR EQUIPMENT_UNAVAILABLE REMOVE Torch is prepared in the command surface, but the equipment runtime is not yet wired.");
  }
}
