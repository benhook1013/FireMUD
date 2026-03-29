package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the gameplay-binding PLAY command after login. */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class PlayCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(PlayCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.play.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.play.failures";

  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final GameLogicProperties gameLogicProperties;
  private final MeterRegistry meterRegistry;

  public PlayCommandHandlingResult handle(String sessionId, TextCommand command) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(command, "command must not be null");

    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    String tenantTag =
        maybeContext.map(context -> Long.toString(context.tenantId())).orElse("unknown");
    meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", tenantTag).increment();

    if (maybeContext.isEmpty()) {
      logFailure(
          GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
          GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE,
          tenantTag,
          null);
      return new PlayCommandHandlingResult(
          CommandEnqueueResult.failure(
              GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
              GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE),
          null);
    }

    List<String> args = command.args();
    if (args.isEmpty()) {
      logFailure(
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_CODE,
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_MESSAGE,
          tenantTag,
          null);
      return new PlayCommandHandlingResult(
          CommandEnqueueResult.failure(
              GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_CODE,
              GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_MESSAGE),
          null);
    }

    SessionContext context = maybeContext.get();
    if (StringUtils.hasText(context.roomInstanceId())) {
      String responseText = formatSuccessResponse(args);
      return new PlayCommandHandlingResult(CommandEnqueueResult.success(), responseText);
    }

    String world = args.get(0);
    String character = args.size() > 1 ? args.get(1) : null;
    SessionContext updated =
        new SessionContext(
            context.sessionId(),
            context.tenantId(),
            context.accountId(),
            context.characterId(),
            context.gameInstanceId(),
            gameLogicProperties.getDefaultRoomId(),
            context.jwt());
    sessionContextService.save(updated);

    String responseText = formatSuccessResponse(world, character);
    return new PlayCommandHandlingResult(CommandEnqueueResult.success(), responseText);
  }

  private String formatSuccessResponse(List<String> args) {
    String world = args.get(0);
    String character = args.size() > 1 ? args.get(1) : null;
    return formatSuccessResponse(world, character);
  }

  private String formatSuccessResponse(String world, String character) {
    String suffix = StringUtils.hasText(character) ? " as " + character : "";
    return "OK PLAY Entered world: " + world + suffix;
  }

  private void logFailure(String errorCode, String message, String tenantTag, RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "tenantId", tenantTag, "error", errorCode).increment();
    if (ex == null) {
      LOG.warn("PLAY failed tenantId={} error={} reason={}", tenantTag, errorCode, message);
    } else {
      LOG.warn("PLAY failed tenantId={} error={} reason={}", tenantTag, errorCode, message, ex);
    }
  }
}
