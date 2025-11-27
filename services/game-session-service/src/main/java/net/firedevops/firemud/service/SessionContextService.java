package net.firedevops.firemud.service;

import java.util.Optional;

/** Manages Redis-backed session context entries created during login. */
public interface SessionContextService {
  void save(SessionContext context);

  Optional<SessionContext> findBySessionId(long tenantId, long sessionId);
}
