package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

/** Represents persisted login context stored in Redis for a session. */
public record SessionContext(
    long sessionId,
    long tenantId,
    long accountId,
    long characterId,
    long gameInstanceId,
    String roomInstanceId,
    String jwt)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public SessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      long characterId,
      long gameInstanceId,
      String jwt) {
    this(sessionId, tenantId, accountId, characterId, gameInstanceId, null, jwt);
  }
}
