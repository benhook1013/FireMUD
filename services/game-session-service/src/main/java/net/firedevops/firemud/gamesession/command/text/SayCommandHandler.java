package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamelogic.v1.BroadcastSayResponse;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Handles authenticated SAY-family text commands by speaking through Game Logic. */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class SayCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(SayCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.say.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.say.failures";

  private final GameLogicClient gameLogicClient;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final GameLogicProperties gameLogicProperties;
  private final MeterRegistry meterRegistry;

  public SayCommandHandlingResult handle(String sessionId, TextCommand command) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(command, "command must not be null");

    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    String tenantTag = determineTenantTag(maybeContext);
    meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", tenantTag).increment();

    if (maybeContext.isEmpty()) {
      logFailure("NOT_AUTHENTICATED", "Login required", tenantTag, null);
      return new SayCommandHandlingResult(
          CommandEnqueueResult.failure("NOT_AUTHENTICATED", "Login required"), null);
    }

    SessionContext context = maybeContext.get();
    List<String> args = command.args();
    if (args.isEmpty()) {
      String reason = "SAY command requires a message";
      logFailure("INVALID_ARGUMENT", reason, tenantTag, null);
      return new SayCommandHandlingResult(
          CommandEnqueueResult.failure("INVALID_ARGUMENT", reason), null);
    }

    String message = args.get(0);
    String aliasToken = extractAliasToken(command.rawLine());

    try {
      BroadcastSayResponse response =
          gameLogicClient.broadcastSay(
              Long.toString(context.tenantId()),
              Long.toString(context.sessionId()),
              Long.toString(context.playerId()),
              gameLogicProperties.getDefaultRoomId(),
              aliasToken,
              message);
      if (!response.getSuccess()) {
        String code = "SAY_NOT_DELIVERED";
        String errorMessage =
            response.hasError() && response.getError().getMessage() != null
                ? response.getError().getMessage()
                : "Message delivery failed";
        String errorTag =
            response.hasError() && response.getError().getCode() != null
                ? response.getError().getCode()
                : "UNAVAILABLE";
        logFailure(errorTag, errorMessage, tenantTag, null);
        return new SayCommandHandlingResult(CommandEnqueueResult.failure(code, errorMessage), null);
      }

      String responseText = formatSuccessResponse(response);
      return new SayCommandHandlingResult(CommandEnqueueResult.success(), responseText);
    } catch (RuntimeException ex) {
      logFailure("UNAVAILABLE", "Game Logic unavailable", tenantTag, ex);
      return new SayCommandHandlingResult(
          CommandEnqueueResult.failure("SAY_NOT_DELIVERED", "Game Logic unavailable"), null);
    }
  }

  private String determineTenantTag(Optional<SessionContext> maybeContext) {
    return maybeContext.map(context -> Long.toString(context.tenantId())).orElse("unknown");
  }

  private String extractAliasToken(String rawLine) {
    if (rawLine == null || rawLine.isBlank()) {
      return "SAY";
    }
    String trimmed = rawLine.trim();
    int firstSpace = trimmed.indexOf(' ');
    String token = firstSpace < 0 ? trimmed : trimmed.substring(0, firstSpace);
    return token.toUpperCase(Locale.ROOT);
  }

  private String formatSuccessResponse(BroadcastSayResponse response) {
    List<String> delivered = response.getDeliveredToList();
    String speaker = delivered.isEmpty() ? "Unknown" : delivered.get(0);
    String deliveredLine = String.join(", ", delivered);
    return String.format(
        "Speaker: %s%nDelivered-To: %s%nMessage: %s",
        speaker, deliveredLine, response.getMessage());
  }

  private void logFailure(String errorTag, String reason, String tenantTag, RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "tenantId", tenantTag, "error", errorTag).increment();
    if (ex == null) {
      LOG.warn("SAY failed tenantId={} error={} reason={}", tenantTag, errorTag, reason);
    } else {
      LOG.warn("SAY failed tenantId={} error={} reason={}", tenantTag, errorTag, reason, ex);
    }
  }
}
