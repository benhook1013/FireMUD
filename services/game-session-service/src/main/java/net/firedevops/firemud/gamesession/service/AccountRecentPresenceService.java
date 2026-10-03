package net.firedevops.firemud.gamesession.service;

import java.util.Collection;
import java.util.Map;

/** Tracks bounded account recent-presence facts used for friend/social last-seen queries. */
public interface AccountRecentPresenceService {
  void recordConnected(SessionContext context);

  void recordActivity(long sessionId);

  void recordDisconnect(long sessionId, AccountRecentPresenceDisposition disposition);

  Map<String, AccountRecentPresenceState> findByAccountIds(
      long tenantId, Collection<String> accountIds);
}
