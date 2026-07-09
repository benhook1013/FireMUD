package net.firedevops.firemud.gamesession.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
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
      new WebSocketOutputProjector(outputRenderer);
  private final PresentationProperties presentation = new PresentationProperties();

  @Test
  void fromOutputBuildsStructuredEntryForReplayableMessage() {
    PlayerOutput output =
        PlayerOutput.message(
            "You say, \"hello\"", "communication.say.actor", Map.of("message", "hello"));
    when(outputRenderer.render(output, "en-NZ", presentation)).thenReturn("You say, \"hello\"");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(
            output, outputRenderer, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text()).isEqualTo("You say, \"hello\"\n");
    assertThat(entry.orElseThrow().hasStructuredOutput()).isTrue();
    assertThat(entry.orElseThrow().payloadType()).isEqualTo("text_message");
  }

  @Test
  void fromOutputSkipsNonReplayableOutput() {
    PlayerOutput output = PlayerOutput.notice("offline only");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(
            output, outputRenderer, outputProjector, "en-NZ", presentation);

    assertThat(entry).isEmpty();
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }

  @Test
  void fromOutputUsesLookRendererForViewReplay() {
    PlayerOutput output =
        PlayerOutput.view(
            new LookViewOutput(
                "room-1", "Room One", "Short desc", "Long desc", true, List.of(), List.of()));
    when(outputRenderer.renderSuccessfulForCommandType(
            TextCommandType.LOOK, List.of(output), "en-NZ", presentation))
        .thenReturn("Room One\nLong desc");

    var entry =
        ReplayableScreenBufferEntries.fromOutput(
            output, outputRenderer, outputProjector, "en-NZ", presentation);

    assertThat(entry).isPresent();
    assertThat(entry.orElseThrow().text()).isEqualTo("Room One\nLong desc\n");
    verify(outputRenderer)
        .renderSuccessfulForCommandType(
            TextCommandType.LOOK, List.of(output), "en-NZ", presentation);
    verify(outputRenderer, never()).render(output, "en-NZ", presentation);
  }
}
