package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.gamelogic.v1.MoveResult;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
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
  private final EffectiveSettingsResolver settingsResolver;
  private final MeterRegistry meterRegistry;

  public MoveCommandHandlingResult handle(SessionContext context, TextCommand command) {
    Objects.requireNonNull(context, "context must not be null");
    Objects.requireNonNull(command, "command must not be null");

    String tenantTag = Long.toString(context.tenantId());
    try (GameplayLoggingContext ignored = GameplayLoggingContext.from(context)) {
      meterRegistry.counter(INVOCATIONS_METRIC).increment();
      String direction = extractDirection(command);
      if (!StringUtils.hasText(direction)) {
        return failure(
            "INVALID_ARGUMENT",
            "MOVE command requires a direction",
            "error.move.direction-required",
            Map.of(),
            tenantTag,
            Long.toString(context.gameInstanceId()),
            Long.toString(context.characterId()),
            null);
      }

      try {
        MoveResult response =
            gameLogicClient.resolveMove(
                context,
                StringUtils.hasText(context.roomInstanceId())
                    ? context.roomInstanceId()
                    : gameLogicProperties.getDefaultRoomId(),
                direction,
                StringUtils.hasText(context.localeTag()) ? context.localeTag() : "");
        if (!response.getSuccess()) {
          String code =
              response.hasError() && StringUtils.hasText(response.getError().getCode())
                  ? response.getError().getCode()
                  : "MOVE_UNAVAILABLE";
          String errorMessage =
              response.hasError() && StringUtils.hasText(response.getError().getMessage())
                  ? response.getError().getMessage()
                  : "Move unavailable";
          return failure(
              code,
              errorMessage,
              moveErrorMessageKey(code),
              moveErrorArguments(code, direction, context.roomInstanceId()),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }

        if (!response.hasDestinationLook()) {
          return failure(
              "MOVE_UNAVAILABLE",
              "Move destination unavailable",
              "error.move.destination-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(context.gameInstanceId()),
              Long.toString(context.characterId()),
              null);
        }

        SessionContext updatedContext = updatedContext(context, response);
        sessionContextService.save(updatedContext);
        if (!settingsResolver.movement(updatedContext).postMoveLookEnabled()) {
          return new MoveCommandHandlingResult(CommandEnqueueResult.success(), null);
        }
        return new MoveCommandHandlingResult(
            CommandEnqueueResult.success(),
            lookCommandHandler.toPlayerOutput(
                updatedContext,
                response.getDestinationLook(),
                true,
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.RefreshReason
                    .MOVE_REFRESH,
                net.firedevops.firemud.gamesession.presentation.LookViewOutput.BriefRenderingHint
                    .PREFER_BRIEF));
      } catch (RuntimeException ex) {
        return failure(
            "MOVE_UNAVAILABLE",
            "Game Logic unavailable",
            "error.move.unavailable",
            Map.of(),
            tenantTag,
            Long.toString(context.gameInstanceId()),
            Long.toString(context.characterId()),
            ex);
      }
    }
  }

  private String extractDirection(TextCommand command) {
    return command.directionalPayload().map(TextCommandPayload.Directional::direction).orElse("");
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
        current.jwt(),
        current.localeTag(),
        current.bootstrapGameInstanceId());
  }

  private MoveCommandHandlingResult failure(
      String errorCode,
      String message,
      String messageKey,
      Map<String, String> arguments,
      String tenantTag,
      String gameInstanceTag,
      String characterTag,
      RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "error", errorCode).increment();
    if (ex == null) {
      LOG.warn(
          "MOVE failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message);
    } else {
      LOG.warn(
          "MOVE failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message,
          ex);
    }
    return new MoveCommandHandlingResult(
        CommandEnqueueResult.failure(errorCode, message),
        net.firedevops.firemud.gamesession.presentation.PlayerOutput.error(
            errorCode, message, messageKey, arguments));
  }

  private String moveErrorMessageKey(String errorCode) {
    if (errorCode == null) {
      return null;
    }
    return switch (errorCode) {
      case "INVALID_EXIT" -> "error.move.invalid-exit";
      case "WORLD_UNAVAILABLE" -> "error.move.world-unavailable";
      case "ENTITY_UNAVAILABLE" -> "error.move.entity-unavailable";
      case "NOT_AUTHORIZED" -> "error.move.not-authorized";
      case "MOVE_UNAVAILABLE" -> "error.move.unavailable";
      default -> null;
    };
  }

  private Map<String, String> moveErrorArguments(
      String errorCode, String direction, String roomInstanceId) {
    if ("INVALID_EXIT".equals(errorCode)) {
      return Map.of(
          "direction", direction == null ? "" : direction.toUpperCase(java.util.Locale.ROOT),
          "roomId", roomInstanceId == null ? "" : roomInstanceId);
    }
    return Map.of();
  }
}
