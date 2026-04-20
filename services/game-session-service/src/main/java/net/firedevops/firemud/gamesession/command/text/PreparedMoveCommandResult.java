package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.SessionContext;

public record PreparedMoveCommandResult(
    CommandEnqueueResult commandResult,
    PlayerOutput responseOutput,
    SessionContext updatedContext) {
  boolean success() {
    return commandResult.accepted() && updatedContext != null;
  }
}
