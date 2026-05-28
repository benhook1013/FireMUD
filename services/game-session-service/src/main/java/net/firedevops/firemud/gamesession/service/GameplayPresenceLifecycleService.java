package net.firedevops.firemud.gamesession.service;

/** Coordinates authoritative gameplay presence and bounded recent-presence lifecycle updates. */
public interface GameplayPresenceLifecycleService {
  void registerConnected(SessionContext context);

  void recordActivity(long sessionId, boolean meaningfulGameplayActivity);

  void clearGameplayBinding(SessionContext context, String clearReason);

  void recordDisconnected(long sessionId, AccountRecentPresenceDisposition disposition);
}
