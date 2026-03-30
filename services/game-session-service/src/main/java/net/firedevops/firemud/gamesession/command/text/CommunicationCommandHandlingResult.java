package net.firedevops.firemud.gamesession.command.text;

import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;

/** Result wrapper for communication commands handled directly by Game Session. */
public record CommunicationCommandHandlingResult(
    CommandEnqueueResult commandResult, String responseText) {

  public CommunicationCommandHandlingResult {
    if (commandResult == null) {
      throw new IllegalArgumentException("commandResult must not be null");
    }
  }
}
