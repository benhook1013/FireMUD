package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Objects;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;

/** Structured result of handling a PLAY command. */
public record PlayCommandHandlingResult(
    CommandEnqueueResult commandResult,
    List<PlayerOutput> outputs,
    boolean reconnectRedrawRecommended) {

  public PlayCommandHandlingResult {
    Objects.requireNonNull(commandResult, "commandResult must not be null");
    outputs = outputs == null ? List.of() : List.copyOf(outputs);
  }

  public PlayCommandHandlingResult(CommandEnqueueResult commandResult, List<PlayerOutput> outputs) {
    this(commandResult, outputs, false);
  }
}
