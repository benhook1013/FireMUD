package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;

/** Service handling game instance lifecycle operations. */
public interface GameInstanceService {
  default GameInstanceDto startSession(StartSessionRequest request) {
    return startSession(request, false);
  }

  GameInstanceDto startSession(StartSessionRequest request, boolean replaceExistingFirst);

  /** Starts or resumes a non-replacing, run-owned local fixture launch. */
  RunOwnedInitialLaunchResult startRunOwnedInitialLaunch(StartSessionRequest request);

  GameInstanceDto stopSession(long sessionId);

  GameInstanceDto restartSession(long sessionId);
}
