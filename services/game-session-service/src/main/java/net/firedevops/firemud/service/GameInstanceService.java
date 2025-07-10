package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;

/** Service handling game instance lifecycle operations. */
public interface GameInstanceService {
  GameInstanceDto startSession(StartSessionRequest request);
}
