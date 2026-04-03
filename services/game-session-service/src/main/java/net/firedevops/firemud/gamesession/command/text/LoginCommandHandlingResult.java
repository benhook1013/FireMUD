package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

/** Structured result from handling a LOGIN/LOGON command. */
public record LoginCommandHandlingResult(
    CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {

  public LoginCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }
}
