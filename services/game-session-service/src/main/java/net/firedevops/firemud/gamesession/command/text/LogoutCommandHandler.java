package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameInstanceService;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.stereotype.Component;

/** Handles deliberate player logout distinct from reconnect-loss recovery. */
@Component
public final class LogoutCommandHandler {
  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final GameInstanceService gameInstanceService;
  private final GameplayPresenceService gameplayPresenceService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final ScreenBufferService screenBufferService;

  public LogoutCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      GameInstanceService gameInstanceService,
      GameplayPresenceService gameplayPresenceService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      ScreenBufferService screenBufferService) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.gameInstanceService =
        Objects.requireNonNull(gameInstanceService, "gameInstanceService must not be null");
    this.gameplayPresenceService =
        Objects.requireNonNull(gameplayPresenceService, "gameplayPresenceService must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.screenBufferService =
        Objects.requireNonNull(screenBufferService, "screenBufferService must not be null");
  }

  public LogoutCommandHandlingResult handle(String sessionId) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    if (maybeContext.isEmpty()) {
      return failure(
          "NOT_LOGGED_IN", "You are not logged in.", "error.logout.not-logged-in", Map.of());
    }

    SessionContext context = maybeContext.orElseThrow();
    try {
      if (context.gameInstanceId() > 0) {
        gameInstanceService.stopSession(context.gameInstanceId());
      }
      if (context.gameInstanceId() > 0 && context.characterId() > 0) {
        screenBufferService.clear(
            context.tenantId(), context.gameInstanceId(), context.characterId());
      }
      gameplayPresenceService.removeBySessionId(context.sessionId());
      firstPartyConnectContextRegistry.unregister(context.sessionId());
      sessionContextService.deleteBySessionId(context.tenantId(), context.sessionId());
      return new LogoutCommandHandlingResult(
          CommandEnqueueResult.success(),
          List.of(PlayerOutput.notice("Logged out.", "notice.logout.success", Map.of())));
    } catch (IllegalArgumentException | IllegalStateException ex) {
      return failure(
          "LOGOUT_FAILED",
          "Logout failed.",
          "error.logout.failed",
          Map.of("reason", Optional.ofNullable(ex.getMessage()).orElse("unknown")));
    }
  }

  private LogoutCommandHandlingResult failure(
      String code, String message, String messageKey, Map<String, String> arguments) {
    return new LogoutCommandHandlingResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, arguments)));
  }
}
