package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tracks currently connected in-world gameplay presences on the current runtime. */
public interface GameplayPresenceService {
  void registerConnected(SessionContext context);

  void setExplicitAfk(long sessionId, boolean explicitAfk);

  void recordCommandActivity(long sessionId, boolean meaningfulGameplayActivity);

  void removeBySessionId(long sessionId);

  List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId);

  Map<Long, GameplayPresence> findConnectedByAccountIds(long tenantId, Collection<Long> accountIds);

  Optional<GameplayPresence> findConnectedBySessionId(long sessionId);
}
