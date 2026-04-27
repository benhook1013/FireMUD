package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

public record ActionStateCommandHandlingResult(
    CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {
  public ActionStateCommandHandlingResult {
    outputs = List.copyOf(outputs == null ? List.of() : outputs);
  }
}
