package net.firedevops.firemud.gamesession.command.text;

import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Structured result from handling a LOGIN/LOGON command. */
public record LoginCommandHandlingResult(CommandEnqueueResult commandResult, String responseText) {

  public LoginCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
  }
}
