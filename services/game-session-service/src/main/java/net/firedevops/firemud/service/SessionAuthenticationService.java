package net.firedevops.firemud.service;

import java.util.Optional;
import net.firedevops.firemud.config.GameSessionProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Determines whether a session has already completed login. */
@Component
public final class SessionAuthenticationService {
  private final SessionContextService sessionContextService;
  private final GameSessionProperties properties;

  @Autowired
  public SessionAuthenticationService(
      SessionContextService sessionContextService, GameSessionProperties properties) {
    this.sessionContextService = sessionContextService;
    this.properties = properties;
  }

  public boolean isAuthenticated(String sessionIdText) {
    if (!properties.isRequireAuthenticatedCommands()) {
      return true;
    }
    Optional<Long> maybeSessionId = parseSessionId(sessionIdText);
    return maybeSessionId.flatMap(sessionContextService::findBySessionId).isPresent();
  }

  private Optional<Long> parseSessionId(String text) {
    try {
      return Optional.of(Long.parseLong(text));
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }
}
