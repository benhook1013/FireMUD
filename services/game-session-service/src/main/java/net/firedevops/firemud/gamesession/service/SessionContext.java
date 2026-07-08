package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

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
    String playableStateScope,
    String connectScopeId,
    String connectRequestId)
    implements Serializable {
  private static final long serialVersionUID = 1L;
  private static final String CANONICAL_RUNTIME_ROOM_PREFIX = "R-";
  private static final Pattern LEGACY_NUMERIC_RUNTIME_ROOM_ID_PATTERN =
      Pattern.compile("^(?:room-)?([1-9][0-9]*)$");

  public SessionContext {
    loginName = loginName == null ? null : loginName.trim();
    characterName = characterName == null ? null : characterName.trim();
    roomInstanceId = normalizeRoomInstanceId(roomInstanceId);
    localeTag = normalizeLocaleTag(localeTag);
    worldSlug = normalizeSlug(worldSlug);
    realmSlug = normalizeSlug(realmSlug);
    playableStateScope = normalizeScope(playableStateScope);
    connectScopeId = normalizeText(connectScopeId);
    connectRequestId = normalizeText(connectRequestId);
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
      long bootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion,
      String playableStateScope) {
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
        worldSlug,
        realmSlug,
        pointerVersion,
        playableStateScope,
        null,
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
        null,
        null,
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
        null,
        null,
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
        null,
        null,
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
        null,
        null,
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
        null,
        null,
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
        null,
        null,
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
    return normalizeText(slug);
  }

  private static String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return null;
    }
    return scope.trim().toUpperCase(Locale.ROOT);
  }

  private static String normalizeText(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    return text.trim();
  }

  private static String normalizeRoomInstanceId(String roomInstanceId) {
    String normalized = normalizeText(roomInstanceId);
    if (normalized == null) {
      return null;
    }
    var matcher = LEGACY_NUMERIC_RUNTIME_ROOM_ID_PATTERN.matcher(normalized);
    if (!matcher.matches()) {
      return normalized;
    }
    return CANONICAL_RUNTIME_ROOM_PREFIX + matcher.group(1);
  }

  public boolean hasGameplayIdentity() {
    return gameInstanceId > 0 && characterId > 0;
  }

  public boolean hasGameplayBinding() {
    return gameInstanceId > 0
        || characterId > 0
        || roomInstanceId != null && !roomInstanceId.isBlank();
  }

  public boolean hasGameplayRegionBinding() {
    return gameInstanceId > 0
        && characterId > 0
        && roomInstanceId != null
        && !roomInstanceId.isBlank();
  }

  public static boolean hasGameplayRegionBindingOrFalse(SessionContext context) {
    return context != null && context.hasGameplayRegionBinding();
  }

  public boolean sameGameplayIdentity(SessionContext that) {
    if (that == null) {
      return false;
    }
    return sessionId == that.sessionId()
        && tenantId == that.tenantId()
        && accountId == that.accountId()
        && characterId == that.characterId()
        && gameInstanceId == that.gameInstanceId();
  }

  public Optional<FirstPartyConnectContext> persistedFirstPartyConnectContext() {
    if (accountId <= 0 || tenantId <= 0) {
      return Optional.empty();
    }
    FirstPartyConnectContext connectContext =
        new FirstPartyConnectContext(
            accountId,
            tenantId,
            worldSlug,
            realmSlug,
            bootstrapGameInstanceId,
            pointerVersion,
            connectScopeId,
            null,
            connectRequestId,
            null);
    return connectContext.hasCompleteRoutingScope()
        ? Optional.of(connectContext)
        : Optional.empty();
  }

  public boolean hasPartialPersistedFirstPartyConnectContext() {
    if (accountId <= 0 || tenantId <= 0) {
      return false;
    }
    boolean hasAnyPersistedSelectorField = connectScopeId != null || connectRequestId != null;
    return hasAnyPersistedSelectorField && persistedFirstPartyConnectContext().isEmpty();
  }
}
