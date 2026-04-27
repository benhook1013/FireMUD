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
    long bootstrapGameInstanceId,
    String worldSlug,
    String realmSlug,
    long pointerVersion,
    String playableStateScope)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public SessionContext {
    loginName = loginName == null ? null : loginName.trim();
    characterName = characterName == null ? null : characterName.trim();
    localeTag = normalizeLocaleTag(localeTag);
    worldSlug = normalizeSlug(worldSlug);
    realmSlug = normalizeSlug(realmSlug);
    playableStateScope = normalizeScope(playableStateScope);
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
        bootstrapGameInstanceId,
        null,
        null,
        0L,
        null);
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
      String localeTag,
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
        localeTag,
        bootstrapGameInstanceId,
        null,
        null,
        0L,
        null);
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
        gameInstanceId,
        null,
        null,
        0L,
        null);
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
        gameInstanceId,
        null,
        null,
        0L,
        null);
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
        gameInstanceId,
        null,
        null,
        0L,
        null);
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
        gameInstanceId,
        null,
        null,
        0L,
        null);
  }

  private static String normalizeLocaleTag(String localeTag) {
    if (localeTag == null || localeTag.isBlank()) {
      return null;
    }
    String normalized = Locale.forLanguageTag(localeTag.trim()).toLanguageTag();
    return "und".equals(normalized) ? null : normalized;
  }

  private static String normalizeSlug(String slug) {
    if (slug == null || slug.isBlank()) {
      return null;
    }
    return slug.trim();
  }

  private static String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return null;
    }
    return scope.trim().toUpperCase(Locale.ROOT);
  }
}
