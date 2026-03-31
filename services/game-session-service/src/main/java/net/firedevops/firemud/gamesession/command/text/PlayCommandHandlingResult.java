package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Result of handling a PLAY command. */
public record PlayCommandHandlingResult(
    CommandEnqueueResult commandResult, String responseText, boolean reconnectRedrawRecommended) {

  public PlayCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }

  public PlayCommandHandlingResult(CommandEnqueueResult commandResult, String responseText) {
    this(commandResult, responseText, false);
  }
}
