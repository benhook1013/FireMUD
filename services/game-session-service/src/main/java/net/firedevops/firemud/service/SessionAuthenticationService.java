package net.firedevops.firemud.service;

import java.util.Optional;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.repository.GameInstanceRepository;
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
    this.sessionContextService = sessionContextService;
    this.properties = properties;
    this.gameInstanceRepository = gameInstanceRepository;
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
    return gameInstanceRepository
        .findById(sessionId)
        .flatMap(gameInstance -> sessionContextService.findBySessionId(gameInstance.getTenantId(), sessionId))
        .isPresent();
  }

  private Optional<Long> parseSessionId(String text) {
    try {
      return Optional.of(Long.parseLong(text));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
