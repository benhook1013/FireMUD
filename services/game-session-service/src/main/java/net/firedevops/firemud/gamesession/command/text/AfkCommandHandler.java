package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.stereotype.Component;

@Component
public final class AfkCommandHandler {
  private final SessionAuthenticationService sessionAuthenticationService;
  private final GameplayPresenceService gameplayPresenceService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected services are stored only for internal command handling")
  public AfkCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      GameplayPresenceService gameplayPresenceService) {
    this.sessionAuthenticationService = sessionAuthenticationService;
    this.gameplayPresenceService = gameplayPresenceService;
  }

  public AfkCommandHandlingResult handle(String sessionId, TextCommand command) {
    Optional<SessionContext> maybeContext =
        sessionAuthenticationService
            .resolveSessionContext(sessionId)
            .filter(context -> context.gameInstanceId() > 0);
    if (maybeContext.isEmpty()) {
      return failure("NOT_PLAYING", "You are not in the game.");
    }
    if (!(command.payload() instanceof TextCommandPayload.AfkRequest request)
        || request.enabled() == null) {
      return failure("INVALID_ARGUMENT", "Usage: AFK [ON|OFF]");
    }
    boolean enabled = request.enabled();
    gameplayPresenceService.setExplicitAfk(maybeContext.orElseThrow().sessionId(), enabled);
    return new AfkCommandHandlingResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.notice(enabled ? "AFK enabled." : "AFK cleared.")));
  }

  private AfkCommandHandlingResult failure(String code, String message) {
    return new AfkCommandHandlingResult(
        CommandEnqueueResult.failure(code, message), List.of(PlayerOutput.notice(message)));
  }
}
