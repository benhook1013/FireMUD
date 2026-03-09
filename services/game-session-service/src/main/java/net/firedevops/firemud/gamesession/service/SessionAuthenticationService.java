package net.firedevops.firemud.gamesession.service;

import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.devisolated.DevIsolatedGameInstanceRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Determines whether a session has already completed login. */
@Component
public final class SessionAuthenticationService {
  private final SessionContextService sessionContextService;
  private final GameSessionProperties properties;
  private final GameInstanceRepository gameInstanceRepository;
  private final DevIsolatedProperties devIsolatedProperties;
  private final DevIsolatedGameInstanceRegistry devIsolatedGameInstanceRegistry;

  @Autowired
  public SessionAuthenticationService(
      SessionContextService sessionContextService,
      GameSessionProperties properties,
      GameInstanceRepository gameInstanceRepository,
      DevIsolatedProperties devIsolatedProperties,
      ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedGameInstanceRegistryProvider) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.devIsolatedProperties =
        Objects.requireNonNull(devIsolatedProperties, "devIsolatedProperties must not be null");
    this.devIsolatedGameInstanceRegistry =
        Objects.requireNonNull(
                devIsolatedGameInstanceRegistryProvider,
                "devIsolatedGameInstanceRegistryProvider must not be null")
            .getIfAvailable();
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
    return sessionContextService.findByTenantAndSessionId(maybeTenantId.get(), sessionId);
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
    return sessionContextService.findByTenantAndSessionId(tenantId, sessionId).isPresent();
  }

  private Optional<Long> parseSessionId(String text) {
    try {
      return Optional.of(Long.parseLong(text));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private Optional<Long> findTenantId(long sessionId) {
    Optional<Long> tenantFromRepository =
        gameInstanceRepository.findById(sessionId).map(GameInstance::getTenantId);
    if (tenantFromRepository.isPresent()) {
      return tenantFromRepository;
    }
    if (!devIsolatedProperties.isDevIsolated() || devIsolatedGameInstanceRegistry == null) {
      return Optional.empty();
    }
    return devIsolatedGameInstanceRegistry.findById(sessionId).map(GameInstance::getTenantId);
  }
}
