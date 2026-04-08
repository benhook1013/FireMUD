package net.firedevops.firemud.gamesession.service;

import java.util.List;

/** Tracks currently connected in-world gameplay presences on the current runtime. */
public interface GameplayPresenceService {
  void registerConnected(SessionContext context);

  void removeBySessionId(long sessionId);

  List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId);
}
