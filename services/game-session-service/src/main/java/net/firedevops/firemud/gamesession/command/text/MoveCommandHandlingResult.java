package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Result of handling an authenticated MOVE-family command. */
public record MoveCommandHandlingResult(CommandEnqueueResult commandResult, String responseText) {

  public MoveCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }
}
