package net.firedevops.firemud.command.text;

import java.util.Objects;
import net.firedevops.firemud.dto.CommandEnqueueResult;

/** Result of interpreting a text command, including any immediate response text. */
public record TextCommandInterpretationResult(
    CommandEnqueueResult commandResult, String responseText) {

  public TextCommandInterpretationResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }

  public boolean hasResponse() {
    return responseText != null && !responseText.isBlank();
  }
}
