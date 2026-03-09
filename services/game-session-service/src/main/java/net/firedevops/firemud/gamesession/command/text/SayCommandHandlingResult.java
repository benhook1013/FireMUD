package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Structured result from handling a SAY-family command. */
public record SayCommandHandlingResult(CommandEnqueueResult commandResult, String responseText) {

  public SayCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }
}
