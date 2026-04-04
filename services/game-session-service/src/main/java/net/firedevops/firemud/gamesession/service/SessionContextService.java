package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

/** Manages Redis-backed session context entries created during login. */
public interface SessionContextService {
  void save(SessionContext context);

  Optional<SessionContext> findBySessionId(long sessionId);

  Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId);

  Optional<SessionContext> findByGameplayIdentity(
      long tenantId, long gameInstanceId, long characterId);

  Optional<SessionContext> findByGameplayName(
      long tenantId, long gameInstanceId, String characterName);

  void deleteBySessionId(long tenantId, long sessionId);
}
