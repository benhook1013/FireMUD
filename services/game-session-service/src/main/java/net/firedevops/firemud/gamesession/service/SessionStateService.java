package net.firedevops.firemud.gamesession.service;

import net.firedevops.firemud.gamesession.dto.GameInstanceDto;

/** Manages session state in Redis for reconnect recovery. */
public interface SessionStateService {
  /** Persist session details under a tenant-prefixed key. */
  void saveState(GameInstanceDto dto);

  /** Remove session details once a game instance stops. */
  void deleteState(Long tenantId, Long sessionId);
}
