package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

/** Structured result wrapper for communication commands handled directly by Game Session. */
public record CommunicationCommandHandlingResult(
    CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {

  public CommunicationCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }
}
