package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;
import java.util.Locale;

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
    String localeTag,
    long bootstrapGameInstanceId)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public SessionContext {
    loginName = loginName == null ? null : loginName.trim();
    characterName = characterName == null ? null : characterName.trim();
    localeTag = normalizeLocaleTag(localeTag);
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
      String jwt,
      long bootstrapGameInstanceId) {
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
        null,
        bootstrapGameInstanceId);
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
        null,
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
        null,
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
        null,
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
        null,
        gameInstanceId);
  }

  private static String normalizeLocaleTag(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return null;
    }
    String normalized = Locale.forLanguageTag(localeTag.trim()).toLanguageTag();
    return "und".equals(normalized) ? null : normalized;
  }
}
