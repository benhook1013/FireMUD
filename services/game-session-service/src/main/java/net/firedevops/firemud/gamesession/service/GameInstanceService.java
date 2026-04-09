package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;

/** Service handling game instance lifecycle operations. */
public interface GameInstanceService {
  default GameInstanceDto startSession(StartSessionRequest request) {
    return startSession(request, true);
  }

  GameInstanceDto startSession(StartSessionRequest request, boolean replaceExistingFirst);

  GameInstanceDto stopSession(long sessionId);

  GameInstanceDto restartSession(long sessionId);
}
