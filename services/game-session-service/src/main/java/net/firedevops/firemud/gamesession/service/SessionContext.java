package net.firedevops.firemud.gamesession.service;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.common.security.JwtClaims;

/** Represents persisted login context stored in Redis for a session. */
public record SessionContext(
    long sessionId,
    long tenantId,
    String accountId,
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
    String connectRequestId,
    FirstPartyConnectContext firstPartyConnectContext)
    implements Serializable {
  private static final long serialVersionUID = 1L;

  public SessionContext(
      long sessionId,
      long tenantId,
      String accountId,
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
      String connectRequestId) {
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
        connectScopeId,
        connectRequestId,
        null);
  }

  public SessionContext {
    loginName = loginName == null ? null : loginName.trim();
    characterName = characterName == null ? null : characterName.trim();
    roomInstanceId = normalizeText(roomInstanceId);
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
      String accountId,
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
      String accountId,
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
      String accountId,
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
      String accountId,
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
      String accountId,
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
      String accountId,
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
      String accountId,
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

  /**
   * Returns the non-secret Redis projection without changing this immutable input.
   *
   * <p>Redis reads return this projection with a null JWT. An incoming context may carry a
   * transient credential, but this method neither retains it in a process-local store nor
   * reattaches it after a read. Authentication and downstream credentials must not depend on a
   * stored context JWT.
   */
  public SessionContext withoutJwt() {
    if (jwt == null) {
      return this;
    }
    return new SessionContext(
        sessionId,
        tenantId,
        accountId,
        loginName,
        characterId,
        characterName,
        gameInstanceId,
        roomInstanceId,
        null,
        localeTag,
        bootstrapGameInstanceId,
        worldSlug,
        realmSlug,
        pointerVersion,
        playableStateScope,
        connectScopeId,
        connectRequestId,
        firstPartyConnectContext);
  }

  public boolean hasGameplayIdentity() {
    return gameInstanceId > 0 && characterId > 0;
  }

  /**
   * Uses the shared ordinary Account identity contract without interpreting private row handles.
   */
  public static boolean isCanonicalAccountIdentity(String accountId) {
    try {
      JwtClaims.requireCanonicalAccountUuid(accountId, "accountId");
      return true;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  public boolean hasAccountIdentity() {
    return isCanonicalAccountIdentity(accountId);
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
        && Objects.equals(accountId, that.accountId())
        && characterId == that.characterId()
        && gameInstanceId == that.gameInstanceId();
  }

  public Optional<FirstPartyConnectContext> persistedFirstPartyConnectContext() {
    return Optional.ofNullable(firstPartyConnectContext);
  }

  public boolean hasPartialPersistedFirstPartyConnectContext() {
    return firstPartyConnectContext == null && (connectScopeId != null || connectRequestId != null);
  }

  public SessionContext withFirstPartyConnectContext(FirstPartyConnectContext context) {
    return new SessionContext(
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
        connectScopeId,
        connectRequestId,
        context);
  }
}
