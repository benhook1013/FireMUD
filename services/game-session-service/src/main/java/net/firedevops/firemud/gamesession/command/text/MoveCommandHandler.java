package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles authenticated MOVE-family commands through Game Logic. */
@Component
@RequiredArgsConstructor
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class MoveCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(MoveCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.move.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.move.failures";

  private final GameLogicClient gameLogicClient;
  private final SessionContextService sessionContextService;
  private final LookCommandHandler lookCommandHandler;
  private final GameLogicProperties gameLogicProperties;
  private final MeterRegistry meterRegistry;

  public MoveCommandHandlingResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    String tenantTag = Long.toString(context.tenantId());
    meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", tenantTag).increment();
    String direction = extractDirection(command.args());
    if (!StringUtils.hasText(direction)) {
      return failure("INVALID_ARGUMENT", "MOVE command requires a direction", tenantTag, null);
    }

    try {
      MoveResult response =
          gameLogicClient.resolveMove(
              Long.toString(context.tenantId()),
              Long.toString(context.sessionId()),
              Long.toString(context.characterId()),
              StringUtils.hasText(context.roomInstanceId())
                  ? context.roomInstanceId()
                  : gameLogicProperties.getDefaultRoomId(),
              direction);
      if (!response.getSuccess()) {
        String code =
            response.hasError() && StringUtils.hasText(response.getError().getCode())
                ? response.getError().getCode()
                : "MOVE_UNAVAILABLE";
        String errorMessage =
            response.hasError() && StringUtils.hasText(response.getError().getMessage())
                ? response.getError().getMessage()
                : "Move unavailable";
        return failure(code, errorMessage, tenantTag, null);
      }

      if (!response.hasDestinationLook()) {
        return failure("MOVE_UNAVAILABLE", "Move destination unavailable", tenantTag, null);
      }

      SessionContext updatedContext = updatedContext(context, response);
      sessionContextService.save(updatedContext);
      return new MoveCommandHandlingResult(
          CommandEnqueueResult.success(),
          lookCommandHandler.renderProtocol(updatedContext, response.getDestinationLook()));
    } catch (RuntimeException ex) {
      return failure("MOVE_UNAVAILABLE", "Game Logic unavailable", tenantTag, ex);
    }
  }

  private String extractDirection(List<String> args) {
    if (args == null || args.isEmpty()) {
      return "";
    }
    return args.get(0);
  }

  private SessionContext updatedContext(SessionContext current, MoveResult response) {
    String destinationRoomId = response.getDestinationLook().getRoomInstance().getRoomInstanceId();
    return new SessionContext(
        current.sessionId(),
        current.tenantId(),
        current.accountId(),
        current.loginName(),
        current.characterId(),
        current.characterName(),
        current.gameInstanceId(),
        destinationRoomId,
        current.jwt());
  }

  private MoveCommandHandlingResult failure(
      String errorCode, String message, String tenantTag, RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "tenantId", tenantTag, "error", errorCode).increment();
    if (ex == null) {
      LOG.warn("MOVE failed tenantId={} error={} reason={}", tenantTag, errorCode, message);
    } else {
      LOG.warn("MOVE failed tenantId={} error={} reason={}", tenantTag, errorCode, message, ex);
    }
    return new MoveCommandHandlingResult(CommandEnqueueResult.failure(errorCode, message), null);
  }
}
