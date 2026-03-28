package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Result of interpreting a text command, including any immediate response text. */
public record TextCommandInterpretationResult(
    CommandEnqueueResult commandResult, String responseText, boolean protocolResponse) {

  public TextCommandInterpretationResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }

  public TextCommandInterpretationResult(CommandEnqueueResult commandResult, String responseText) {
    this(commandResult, responseText, false);
  }

  public boolean hasResponse() {
    return responseText != null && !responseText.isBlank();
  }
}
