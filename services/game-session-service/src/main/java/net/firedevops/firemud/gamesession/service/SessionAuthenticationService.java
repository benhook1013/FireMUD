package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Determines whether a session has already completed login. */
@Component
public final class SessionAuthenticationService {
  private final SessionContextService sessionContextService;
  private final GameSessionProperties properties;
  private final GameInstanceRepository gameInstanceRepository;

  @Autowired
  public SessionAuthenticationService(
      SessionContextService sessionContextService,
      GameSessionProperties properties,
      GameInstanceRepository gameInstanceRepository) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
  }

  public Optional<SessionContext> resolveSessionContext(String sessionIdText) {
    Optional<Long> maybeSessionId = parseSessionId(sessionIdText);
    if (maybeSessionId.isEmpty()) {
      return Optional.empty();
    }
    long sessionId = maybeSessionId.get();
    Optional<Long> maybeTenantId = findTenantId(sessionId);
    if (maybeTenantId.isEmpty()) {
      return Optional.empty();
    }
    return sessionContextService
        .findByTenantAndSessionId(maybeTenantId.get(), sessionId)
        .filter(this::isAuthenticatedContext);
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
        .filter(this::isAuthenticatedContext)
        .isPresent();
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
    Optional<Long> tenantFromContext =
        sessionContextService.findBySessionId(sessionId).map(SessionContext::tenantId);
    if (tenantFromContext.isPresent()) {
      return tenantFromContext;
    }
    Optional<Long> tenantFromRepository =
        gameInstanceRepository.findById(sessionId).map(GameInstance::getTenantId);
    if (tenantFromRepository.isPresent()) {
      return tenantFromRepository;
    }
    return Optional.empty();
  }
}
