package net.firedevops.firemud.gamesession.command.text;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.AccountIds;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Handles deliberate player logout, which remains unavailable until its durable fence exists. */
@Component
public final class LogoutCommandHandler {
  private final SessionContextService sessionContextService;

  @Autowired
  public LogoutCommandHandler(SessionContextService sessionContextService) {
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
  }

  public LogoutCommandHandlingResult handle(String sessionId, TextCommand command) {
    Objects.requireNonNull(command, "command must not be null");

    Optional<SessionContext> maybeAuthenticatedContext;
    try {
      maybeAuthenticatedContext =
          SessionIdParsing.parse(sessionId)
              .optionalValue()
              .flatMap(sessionContextService::findBySessionId)
              .filter(context -> AccountIds.isCanonicalNonNilUuid(context.accountId()));
    } catch (RuntimeException ex) {
      return unavailable();
    }

    if (maybeAuthenticatedContext.isEmpty()) {
      return failure(
          "NOT_LOGGED_IN", "You are not logged in.", "error.logout.not-logged-in", Map.of());
    }

    return unavailable();
  }

  private LogoutCommandHandlingResult unavailable() {
    return failure(
        "LOGOUT_UNAVAILABLE",
        "Logout is temporarily unavailable. Please try again.",
        "error.logout.unavailable",
        Map.of());
  }

  private LogoutCommandHandlingResult failure(
      String code, String message, String messageKey, Map<String, String> arguments) {
    return new LogoutCommandHandlingResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, arguments)));
  }
}
