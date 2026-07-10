package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.presentation.InventoryViewOutput;
import net.firedevops.firemud.gamesession.presentation.LookViewOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.websocket.WebSocketOutputProjector;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ReplayableScreenBufferEntriesTest {
  private final TextPlayerOutputRenderer outputRenderer =
      Mockito.mock(TextPlayerOutputRenderer.class);
  private final WebSocketOutputProjector outputProjector =
      Mockito.spy(new WebSocketOutputProjector(outputRenderer));
  private final PresentationProperties presentation = new PresentationProperties();

  @Test
  void fromOutputBuildsStructuredEntryForReplayableMessage() {
    PlayerOutput output =
        PlayerOutput.message(
            "You say, \"hello\"", "communication.say.actor", Map.of("message", "hello"));
    when(outputRenderer.render(output, "en-NZ", presentation)).thenReturn("You say, \"hello\"");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text()).isEqualTo("You say, \"hello\"\n");
    assertThat(entry.orElseThrow().hasStructuredOutput()).isTrue();
    assertThat(entry.orElseThrow().payloadType()).isEqualTo("text_message");
  }

  @Test
  void fromOutputSkipsNonReplayableOutput() {
    PlayerOutput output = PlayerOutput.notice("offline only");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isEmpty();
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void fromOutputUsesLookRendererForViewReplay() {
    PlayerOutput output =
        PlayerOutput.view(
            new LookViewOutput(
                "room-1", "Room One", "Short desc", "Long desc", true, List.of(), List.of()));
    when(outputRenderer.renderSuccessfulForOutput(output, "en-NZ", presentation))
        .thenReturn("Room One\nLong desc");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text()).isEqualTo("Room One\nLong desc\n");
    verify(outputProjector).renderClassicPlayerOutput(output, "en-NZ", presentation);
    verify(outputRenderer).renderSuccessfulForOutput(output, "en-NZ", presentation);
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void fromOutputUsesQuickLookEnvelopeForReplayableQuickLookView() {
    PlayerOutput output =
        PlayerOutput.view(
            new LookViewOutput(
                "room-1",
                "Quick Hall",
                "Quick hall short",
                "Quick hall long",
                false,
                LookViewOutput.RefreshReason.QUICKLOOK,
                List.of(),
                List.of()));
    when(outputRenderer.renderSuccessfulForOutput(output, "en-NZ", presentation))
        .thenReturn("OK QUICKLOOK\nRoom: Quick Hall (ID: room-1)\nShort: Quick hall short\n\n");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text())
        .isEqualTo("OK QUICKLOOK\nRoom: Quick Hall (ID: room-1)\nShort: Quick hall short\n\n\n");
    verify(outputProjector).renderClassicPlayerOutput(output, "en-NZ", presentation);
    verify(outputRenderer).renderSuccessfulForOutput(output, "en-NZ", presentation);
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void fromOutputUsesInventoryRendererForReplayableInventoryView() {
    PlayerOutput output =
        PlayerOutput.view(
            new InventoryViewOutput(
                InventoryViewOutput.Source.INVENTORY,
                "Inventory:",
                List.of("- Torch x2 (A small torch)"),
                List.of(
                    new InventoryViewOutput.ItemEntry(
                        "7", "", "", "torch3", "Torch", "A small torch", 2, ""))));
    when(outputRenderer.renderSuccessfulForOutput(output, "en-NZ", presentation))
        .thenReturn("OK INVENTORY\nInventory:\n- Torch x2 (A small torch)\n\n");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text())
        .isEqualTo("OK INVENTORY\nInventory:\n- Torch x2 (A small torch)\n\n\n");
    assertThat(entry.orElseThrow().payloadType()).isEqualTo("inventory_view");
    assertThat(entry.orElseThrow().payloadJson())
        .contains("\"visibleRef\":\"torch3\"")
        .contains("\"itemName\":\"Torch\"");
    verify(outputProjector).renderClassicPlayerOutput(output, "en-NZ", presentation);
    verify(outputRenderer).renderSuccessfulForOutput(output, "en-NZ", presentation);
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void fromOutputUsesInventorySourceInsteadOfTitlePrefix() {
    PlayerOutput output =
        PlayerOutput.view(
            new InventoryViewOutput(
                InventoryViewOutput.Source.CONTAINER, "Inventory:", List.of("It is empty.")));
    when(outputRenderer.renderSuccessfulForOutput(output, "en-NZ", presentation))
        .thenReturn("OK CONTAINER\nInventory:\nIt is empty.\n\n");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(output, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text())
        .isEqualTo("OK CONTAINER\nInventory:\nIt is empty.\n\n\n");
    verify(outputProjector).renderClassicPlayerOutput(output, "en-NZ", presentation);
    verify(outputRenderer).renderSuccessfulForOutput(output, "en-NZ", presentation);
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }
}
