package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;

/** Service handling game instance lifecycle operations. */
public interface GameInstanceService {
  GameInstanceDto startSession(StartSessionRequest request);

  GameInstanceDto stopSession(long sessionId);

  GameInstanceDto restartSession(long sessionId);
}
