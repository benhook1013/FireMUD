package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Determines whether a session has already completed login. */
@Component
public final class SessionAuthenticationService {
  private final SessionContextService sessionContextService;
  private final GameSessionProperties properties;
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;

  @Autowired
  public SessionAuthenticationService(
      SessionContextService sessionContextService,
      GameSessionProperties properties,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.sessionRoutingNormalizationService =
        Objects.requireNonNull(
            sessionRoutingNormalizationService,
            "sessionRoutingNormalizationService must not be null");
    this.gameplayPresenceLifecycleService =
        Objects.requireNonNull(
            gameplayPresenceLifecycleService, "gameplayPresenceLifecycleService must not be null");
  }

  public Optional<SessionContext> resolveSessionContext(String sessionIdText) {
    return resolveUnverifiedSessionContext(sessionIdText).filter(this::isAuthenticatedContext);
  }

  public Optional<SessionContext> resolveUnverifiedSessionContext(String sessionIdText) {
    Optional<Long> maybeSessionId = parseSessionId(sessionIdText);
    if (maybeSessionId.isEmpty()) {
      return Optional.empty();
    }
    long sessionId = maybeSessionId.get();
    Optional<Long> maybeTenantId = findTenantId(sessionId);
    if (maybeTenantId.isEmpty()) {
      return Optional.empty();
    }
    return resolveUnverifiedSessionContext(maybeTenantId.get(), sessionId);
  }

  public Optional<SessionContext> resolveUnverifiedSessionContext(long tenantId, long sessionId) {
    return sessionContextService
        .findByTenantAndSessionId(tenantId, sessionId)
        .map(this::normalizeResolvedContext);
  }

  public boolean isAuthenticated(String sessionIdText) {
    if (!properties.isRequireAuthenticatedCommands()) {
      return true;
    }
    Optional<Long> maybeSessionId = parseSessionId(sessionIdText);
    if (maybeSessionId.isEmpty()) {
      return false;
    }
    long sessionId = maybeSessionId.get();
    Optional<Long> maybeTenantId = findTenantId(sessionId);
    if (maybeTenantId.isEmpty()) {
      return false;
    }
    long tenantId = maybeTenantId.get();
    return sessionContextService
        .findByTenantAndSessionId(tenantId, sessionId)
        .map(this::normalizeResolvedContext)
        .filter(this::isAuthenticatedContext)
        .isPresent();
  }

  public Optional<SessionContext> resolveByGameplayIdentity(
      long tenantId, long gameInstanceId, long characterId) {
    return sessionContextService
        .findByGameplayIdentity(tenantId, gameInstanceId, characterId)
        .map(this::normalizeResolvedContext);
  }

  public Optional<SessionContext> resolveByGameplayName(
      long tenantId, long gameInstanceId, String characterName) {
    return sessionContextService
        .findByGameplayName(tenantId, gameInstanceId, characterName)
        .map(this::normalizeResolvedContext);
  }

  public SessionContext normalizeResolvedContext(SessionContext context) {
    Objects.requireNonNull(context, "context must not be null");
    SessionContext normalized =
        sessionRoutingNormalizationService.normalizeProjectedContext(context);
    if (normalized.equals(context)) {
      return context;
    }
    gameplayPresenceLifecycleService.clearGameplayBinding(context, "STALE_ADMISSION_POINTER");
    sessionContextService.save(normalized);
    return normalized;
  }

  private boolean isAuthenticatedContext(SessionContext context) {
    return context.accountId() > 0;
  }

  private Optional<Long> parseSessionId(String text) {
    try {
      return Optional.of(Long.parseLong(text));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private Optional<Long> findTenantId(long sessionId) {
    return sessionRoutingNormalizationService.findTenantId(sessionId);
  }
}
