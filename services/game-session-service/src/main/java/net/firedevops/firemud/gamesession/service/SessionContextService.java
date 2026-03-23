package net.firedevops.firemud.gamesession.service;

import java.util.Optional;

/** Manages Redis-backed session context entries created during login. */
public interface SessionContextService {
  void save(SessionContext context);

  Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId);

  Optional<SessionContext> findByAccountAndCharacter(
      long tenantId, long accountId, long characterId);

  void deleteBySessionId(long tenantId, long sessionId);
}
