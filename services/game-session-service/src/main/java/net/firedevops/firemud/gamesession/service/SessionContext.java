package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;

/** Represents persisted login context stored in Redis for a session. */
public record SessionContext(
    long sessionId,
    long tenantId,
    long accountId,
    String loginName,
    long characterId,
    String characterName,
    long gameInstanceId,
    String roomInstanceId,
    String jwt,
    long bootstrapGameInstanceId)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public SessionContext {
    loginName = loginName == null ? null : loginName.trim();
    characterName = characterName == null ? null : characterName.trim();
  }

  public SessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      long characterId,
      long gameInstanceId,
      String roomInstanceId,
      String jwt) {
    this(
        sessionId,
        tenantId,
        accountId,
        null,
        characterId,
        null,
        gameInstanceId,
        roomInstanceId,
        jwt,
        gameInstanceId);
  }

  public SessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      long characterId,
      long gameInstanceId,
      String jwt) {
    this(
        sessionId,
        tenantId,
        accountId,
        null,
        characterId,
        null,
        gameInstanceId,
        null,
        jwt,
        gameInstanceId);
  }

  public SessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      String loginName,
      long characterId,
      String characterName,
      long gameInstanceId,
      String jwt) {
    this(
        sessionId,
        tenantId,
        accountId,
        loginName,
        characterId,
        characterName,
        gameInstanceId,
        null,
        jwt,
        gameInstanceId);
  }

  public SessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      String loginName,
      long characterId,
      String characterName,
      long gameInstanceId,
      String roomInstanceId,
      String jwt) {
    this(
        sessionId,
        tenantId,
        accountId,
        loginName,
        characterId,
        characterName,
        gameInstanceId,
        roomInstanceId,
        jwt,
        gameInstanceId);
  }
}
